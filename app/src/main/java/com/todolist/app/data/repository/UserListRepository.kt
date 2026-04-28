package com.todolist.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.data.model.UserList
import com.todolist.app.data.model.UserListItem
import com.todolist.app.data.model.UserListItemPatch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class UserListRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun getLists(uid: String): Flow<List<UserList>> = callbackFlow {
        val registration = listsCollection(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val lists = snapshot?.documents
                    .orEmpty()
                    .mapNotNull { it.toUserListOrNull() }
                    .sortedWith(compareByDescending<UserList> { it.updatedAt?.seconds ?: 0L })

                trySend(lists).isSuccess
            }

        awaitClose { registration.remove() }
    }

    fun getListItems(uid: String, listIds: List<String>): Flow<List<UserListItem>> = callbackFlow {
        val cleanListIds = listIds.distinct().filter { it.isNotBlank() }
        if (cleanListIds.isEmpty()) {
            trySend(emptyList()).isSuccess
            close()
            return@callbackFlow
        }

        val itemsByList = linkedMapOf<String, List<UserListItem>>()
        val registrations = cleanListIds.map { listId ->
            listItemsCollection(uid, listId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }

                    itemsByList[listId] = snapshot?.documents
                        .orEmpty()
                        .mapNotNull { it.toUserListItemOrNull() }

                    val merged = itemsByList.values
                        .flatten()
                        .sortedForLists()

                    trySend(merged).isSuccess
                }
        }

        awaitClose { registrations.forEach { it.remove() } }
    }

    suspend fun createList(uid: String, name: String) {
        require(uid.isNotBlank()) { "UID invalido para crear lista" }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre de la lista no puede estar vacio" }

        listsCollection(uid)
            .add(
                mapOf(
                    "ownerId" to uid,
                    "name" to cleanName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun renameList(uid: String, listId: String, name: String) {
        if (listId.isBlank()) return
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre de la lista no puede estar vacio" }

        listsCollection(uid)
            .document(listId)
            .update(
                mapOf(
                    "name" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun deleteList(uid: String, listId: String) {
        if (listId.isBlank()) return

        val items = listItemsCollection(uid, listId)
            .get()
            .await()

        firestore.runBatch { batch ->
            items.documents.forEach { document ->
                batch.delete(document.reference)
            }
            batch.delete(listsCollection(uid).document(listId))
        }.await()
    }

    suspend fun createListItem(uid: String, listId: String, text: String) {
        require(uid.isNotBlank()) { "UID invalido para crear item" }
        require(listId.isNotBlank()) { "Lista invalida" }
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "El texto no puede estar vacio" }

        listItemsCollection(uid, listId)
            .add(
                mapOf(
                    "ownerId" to uid,
                    "listId" to listId,
                    "text" to cleanText,
                    "checked" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()

        touchList(uid, listId)
    }

    suspend fun updateListItem(uid: String, listId: String, itemId: String, patch: UserListItemPatch) {
        if (listId.isBlank() || itemId.isBlank()) return

        val itemRef = listItemsCollection(uid, listId).document(itemId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)
            if (!snapshot.exists()) return@runTransaction

            val current = snapshot.toUserListItemOrNull() ?: return@runTransaction
            var hasChanges = false

            patch.text?.trim()?.takeIf { it.isNotEmpty() }?.let { nextText ->
                if (nextText != current.text) {
                    transaction.update(itemRef, "text", nextText)
                    hasChanges = true
                }
            }

            patch.checked?.let { nextChecked ->
                if (nextChecked != current.checked) {
                    transaction.update(itemRef, "checked", nextChecked)
                    hasChanges = true
                }
            }

            if (hasChanges) {
                transaction.update(itemRef, "updatedAt", FieldValue.serverTimestamp())
                transaction.update(
                    listsCollection(uid).document(listId),
                    "updatedAt",
                    FieldValue.serverTimestamp()
                )
            }
        }.await()
    }

    suspend fun deleteListItem(uid: String, listId: String, itemId: String) {
        if (listId.isBlank() || itemId.isBlank()) return

        listItemsCollection(uid, listId)
            .document(itemId)
            .delete()
            .await()

        touchList(uid, listId)
    }

    suspend fun toggleListItemChecked(uid: String, item: UserListItem) {
        updateListItem(uid, item.listId, item.id, UserListItemPatch(checked = !item.checked))
    }

    private suspend fun touchList(uid: String, listId: String) {
        if (listId.isBlank()) return
        listsCollection(uid)
            .document(listId)
            .update("updatedAt", FieldValue.serverTimestamp())
            .await()
    }

    private fun listsCollection(uid: String) = firestore
        .collection("users")
        .document(uid)
        .collection("lists")

    private fun listItemsCollection(uid: String, listId: String): CollectionReference {
        return listsCollection(uid)
            .document(listId)
            .collection("items")
    }

    private fun DocumentSnapshot.toUserListOrNull(): UserList? {
        val name = getString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return UserList(
            id = id,
            ownerId = getString("ownerId").orEmpty(),
            name = name,
            createdAt = get("createdAt") as? Timestamp,
            updatedAt = get("updatedAt") as? Timestamp
        )
    }

    private fun DocumentSnapshot.toUserListItemOrNull(): UserListItem? {
        val text = getString("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val listId = getString("listId").orEmpty().trim()
        if (listId.isEmpty()) return null

        return UserListItem(
            id = id,
            listId = listId,
            ownerId = getString("ownerId").orEmpty(),
            text = text,
            checked = get("checked") as? Boolean ?: false,
            createdAt = get("createdAt") as? Timestamp,
            updatedAt = get("updatedAt") as? Timestamp
        )
    }

    private fun List<UserListItem>.sortedForLists(): List<UserListItem> {
        return sortedWith(
            compareBy<UserListItem> { it.checked }
                .thenByDescending { it.updatedAt?.seconds ?: 0L }
                .thenByDescending { it.createdAt?.seconds ?: 0L }
        )
    }
}
