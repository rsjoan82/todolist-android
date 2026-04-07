package com.todolist.app.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.data.model.Tag
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Locale

class TagRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun getTags(uid: String): Flow<List<Tag>> = callbackFlow {
        val listenerRegistration = firestore.collection("tags")
            .whereEqualTo("ownerId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val tags = snapshot?.documents
                    .orEmpty()
                    .mapNotNull { document ->
                        val name = document.getString("name")?.trim().orEmpty()
                        if (name.isEmpty()) {
                            null
                        } else {
                            Tag(
                                id = document.id,
                                ownerId = document.getString("ownerId").orEmpty(),
                                name = name,
                                color = document.getString("color")
                            )
                        }
                    }
                    .sortedBy { normalizeTagName(it.name) }
                    .orEmpty()

                trySend(tags).isSuccess
            }

        awaitClose { listenerRegistration.remove() }
    }

    suspend fun getTagsOnce(uid: String): List<Tag> {
        return firestore.collection("tags")
            .whereEqualTo("ownerId", uid)
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                val name = document.getString("name")?.trim().orEmpty()
                if (name.isEmpty()) {
                    null
                } else {
                    Tag(
                        id = document.id,
                        ownerId = document.getString("ownerId").orEmpty(),
                        name = name,
                        color = document.getString("color")
                    )
                }
            }
            .sortedBy { normalizeTagName(it.name) }
    }

    suspend fun createTag(uid: String, name: String): Tag {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del tag es obligatorio" }

        val normalizedName = normalizeTagName(cleanName)
        val formattedName = formatTagName(cleanName)
        val existingTag = getTagsOnce(uid)
            .firstOrNull { normalizeTagName(it.name) == normalizedName }
        require(existingTag == null) { "Ya existe un tag con ese nombre" }

        val docRef = firestore.collection("tags").document()
        val tagNameRef = tagNameKeyDocument(uid, normalizedName)

        firestore.runTransaction { transaction ->
            val tagNameSnapshot = transaction.get(tagNameRef)
            require(!tagNameSnapshot.exists()) { "Ya existe un tag con ese nombre" }

            transaction.set(
                docRef,
                mapOf(
                    "ownerId" to uid,
                    "name" to formattedName,
                    "normalizedName" to normalizedName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            transaction.set(
                tagNameRef,
                mapOf(
                    "ownerId" to uid,
                    "normalizedName" to normalizedName,
                    "tagId" to docRef.id,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()

        return Tag(
            id = docRef.id,
            ownerId = uid,
            name = formattedName,
            color = null
        )
    }

    suspend fun renameTag(uid: String, tagId: String, name: String) {
        val cleanTagId = tagId.trim()
        require(cleanTagId.isNotEmpty()) { "Tag invalido" }

        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del tag es obligatorio" }

        val normalizedName = normalizeTagName(cleanName)
        val formattedName = formatTagName(cleanName)
        val existingTag = getTagsOnce(uid)
            .firstOrNull { tag ->
                tag.id != cleanTagId && normalizeTagName(tag.name) == normalizedName
            }
        require(existingTag == null) { "Ya existe un tag con ese nombre" }

        val tagRef = firestore.collection("tags").document(cleanTagId)
        firestore.runTransaction { transaction ->
            val tagSnapshot = transaction.get(tagRef)
            require(tagSnapshot.exists()) { "Tag invalido" }

            val currentName = tagSnapshot.getString("name").orEmpty()
            val currentNormalizedName = tagSnapshot.getString("normalizedName")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: normalizeTagName(currentName)

            if (currentNormalizedName == normalizedName) {
                transaction.update(
                    tagRef,
                    mapOf(
                        "name" to formattedName,
                        "normalizedName" to normalizedName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                return@runTransaction
            }

            val oldTagNameRef = tagNameKeyDocument(uid, currentNormalizedName)
            val newTagNameRef = tagNameKeyDocument(uid, normalizedName)
            val newTagNameSnapshot = transaction.get(newTagNameRef)
            require(!newTagNameSnapshot.exists()) { "Ya existe un tag con ese nombre" }

            transaction.update(
                tagRef,
                mapOf(
                    "name" to formattedName,
                    "normalizedName" to normalizedName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            transaction.delete(oldTagNameRef)
            transaction.set(
                newTagNameRef,
                mapOf(
                    "ownerId" to uid,
                    "normalizedName" to normalizedName,
                    "tagId" to cleanTagId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }.await()
    }

    suspend fun deleteTag(uid: String, tagId: String) {
        val cleanTagId = tagId.trim()
        require(cleanTagId.isNotEmpty()) { "Tag invalido" }

        val tagRef = firestore.collection("tags").document(cleanTagId)
        val tagSnapshot = tagRef.get().await()
        val currentNormalizedName = tagSnapshot.getString("normalizedName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: tagSnapshot.getString("name")
                ?.let(::normalizeTagName)

        val taskDocuments = firestore.collection("users")
            .document(uid)
            .collection("tasks")
            .whereEqualTo("tagId", cleanTagId)
            .get()
            .await()
            .documents

        taskDocuments.chunked(450).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { document ->
                batch.update(
                    document.reference,
                    mapOf(
                        "tagId" to null,
                        "tag" to FieldValue.delete(),
                        "tags" to FieldValue.delete(),
                        "tagIds" to FieldValue.delete(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        }

        firestore.collection("tags")
            .document(cleanTagId)
            .delete()
            .await()

        currentNormalizedName?.let { normalizedName ->
            tagNameKeyDocument(uid, normalizedName)
                .delete()
                .await()
        }
    }

    private fun normalizeTagName(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun formatTagName(value: String): String {
        val clean = value.trim().lowercase(Locale.ROOT)
        return clean.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    private fun tagNameKeyDocument(uid: String, normalizedName: String) = firestore
        .collection("users")
        .document(uid)
        .collection("tagNameKeys")
        .document(hashTagName(normalizedName))

    private fun hashTagName(normalizedName: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(normalizedName.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(((byte.toInt() shr 4) and 0xF).toString(16))
                append((byte.toInt() and 0xF).toString(16))
            }
        }
    }
}
