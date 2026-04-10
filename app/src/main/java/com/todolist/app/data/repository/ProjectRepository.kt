package com.todolist.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.data.model.Project
import com.todolist.app.data.model.ProjectItem
import com.todolist.app.data.model.ProjectItemPatch
import com.todolist.app.data.model.ProjectItemType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ProjectRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun getProjectsOnce(uid: String): List<Project> {
        return projectsCollection(uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toProjectOrNull() }
    }

    suspend fun getProjectItemsOnce(uid: String, projectIds: List<String>): List<ProjectItem> {
        migrateLegacyProjectItems(uid)

        return projectIds
            .distinct()
            .filter { it.isNotBlank() }
            .flatMap { projectId ->
                projectItemsCollection(uid, projectId)
                    .get()
                    .await()
                    .documents
                    .mapNotNull { it.toProjectItemOrNull() }
            }
            .sortedWith(
                compareBy<ProjectItem> { it.done }
                    .thenByDescending { it.updatedAt?.seconds ?: 0L }
                    .thenByDescending { it.createdAt?.seconds ?: 0L }
            )
    }

    fun getProjects(uid: String): Flow<List<Project>> = callbackFlow {
        val registration = projectsCollection(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val projects = snapshot?.documents
                    .orEmpty()
                    .mapNotNull { it.toProjectOrNull() }
                    .sortedWith(compareByDescending<Project> { it.updatedAt?.seconds ?: 0L })

                trySend(projects).isSuccess
            }

        awaitClose { registration.remove() }
    }

    fun getProjectItems(uid: String, projectIds: List<String>): Flow<List<ProjectItem>> = callbackFlow {
        val cleanProjectIds = projectIds.distinct().filter { it.isNotBlank() }
        if (cleanProjectIds.isEmpty()) {
            trySend(emptyList()).isSuccess
            close()
            return@callbackFlow
        }

        val itemsByProject = linkedMapOf<String, List<ProjectItem>>()
        val registrations = cleanProjectIds.map { projectId ->
            projectItemsCollection(uid, projectId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }

                    itemsByProject[projectId] = snapshot?.documents
                        .orEmpty()
                        .mapNotNull { it.toProjectItemOrNull() }

                    val merged = itemsByProject.values
                        .flatten()
                        .sortedWith(
                            compareBy<ProjectItem> { it.done }
                                .thenByDescending { it.updatedAt?.seconds ?: 0L }
                                .thenByDescending { it.createdAt?.seconds ?: 0L }
                        )

                    trySend(merged).isSuccess
                }
        }

        awaitClose { registrations.forEach { it.remove() } }
    }

    suspend fun createProject(uid: String, name: String) {
        require(uid.isNotBlank()) { "UID invalido para crear proyecto" }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del proyecto no puede estar vacio" }

        projectsCollection(uid)
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

    suspend fun renameProject(uid: String, projectId: String, name: String) {
        if (projectId.isBlank()) return
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre del proyecto no puede estar vacio" }

        projectsCollection(uid)
            .document(projectId)
            .update(
                mapOf(
                    "name" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
    }

    suspend fun deleteProject(uid: String, projectId: String) {
        if (projectId.isBlank()) return

        val items = projectItemsCollection(uid, projectId)
            .get()
            .await()

        firestore.runBatch { batch ->
            items.documents.forEach { document ->
                batch.delete(document.reference)
            }
            batch.delete(projectsCollection(uid).document(projectId))
        }.await()
    }

    suspend fun createProjectItem(
        uid: String,
        projectId: String,
        title: String,
        description: String,
        type: ProjectItemType
    ) {
        require(uid.isNotBlank()) { "UID invalido para crear item" }
        require(projectId.isNotBlank()) { "Proyecto invalido" }
        val cleanTitle = title.trim()
        val cleanDescription = description.trim()
        require(cleanTitle.isNotEmpty()) { "El titulo no puede estar vacio" }

        projectItemsCollection(uid, projectId)
            .add(
                mapOf(
                    "ownerId" to uid,
                    "projectId" to projectId,
                    "title" to cleanTitle,
                    "description" to cleanDescription,
                    "type" to type.value,
                    "done" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()

        touchProject(uid, projectId)
    }

    suspend fun updateProjectItem(uid: String, projectId: String, itemId: String, patch: ProjectItemPatch) {
        if (projectId.isBlank() || itemId.isBlank()) return

        val itemRef = projectItemsCollection(uid, projectId).document(itemId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(itemRef)
            if (!snapshot.exists()) return@runTransaction

            val current = snapshot.toProjectItemOrNull() ?: return@runTransaction
            var hasChanges = false

            patch.title?.trim()?.takeIf { it.isNotEmpty() }?.let { nextTitle ->
                if (nextTitle != current.title) {
                    transaction.update(itemRef, "title", nextTitle)
                    hasChanges = true
                }
            }

            patch.description?.trim()?.let { nextDescription ->
                if (nextDescription != current.description) {
                    transaction.update(itemRef, "description", nextDescription)
                    hasChanges = true
                }
            }

            patch.type?.let { nextType ->
                if (nextType != current.type) {
                    transaction.update(itemRef, "type", nextType.value)
                    hasChanges = true
                }
            }

            patch.done?.let { nextDone ->
                if (nextDone != current.done) {
                    transaction.update(itemRef, "done", nextDone)
                    hasChanges = true
                }
            }

            if (hasChanges) {
                transaction.update(itemRef, "updatedAt", FieldValue.serverTimestamp())
                transaction.update(
                    projectsCollection(uid).document(projectId),
                    "updatedAt",
                    FieldValue.serverTimestamp()
                )
            }
        }.await()
    }

    suspend fun deleteProjectItem(uid: String, projectId: String, itemId: String) {
        if (projectId.isBlank() || itemId.isBlank()) return

        projectItemsCollection(uid, projectId)
            .document(itemId)
            .delete()
            .await()

        touchProject(uid, projectId)
    }

    suspend fun toggleProjectItemDone(uid: String, item: ProjectItem) {
        updateProjectItem(uid, item.projectId, item.id, ProjectItemPatch(done = !item.done))
    }

    suspend fun migrateLegacyProjectItems(uid: String) {
        val legacyItems = legacyProjectItemsCollection(uid)
            .get()
            .await()

        if (legacyItems.isEmpty) return

        firestore.runBatch { batch ->
            legacyItems.documents.forEach { legacyDocument ->
                val projectId = legacyDocument.getString("projectId").orEmpty().trim()
                if (projectId.isEmpty()) return@forEach

                val legacyText = legacyDocument.getString("text")?.trim()?.takeIf { it.isNotEmpty() }
                val title = legacyDocument.getString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: legacyText ?: return@forEach
                val description = legacyDocument.getString("description")?.trim() ?: legacyText.orEmpty()

                val target = projectItemsCollection(uid, projectId).document(legacyDocument.id)
                val payload = mutableMapOf<String, Any?>(
                    "ownerId" to (legacyDocument.getString("ownerId")?.takeIf { it.isNotBlank() } ?: uid),
                    "projectId" to projectId,
                    "title" to title,
                    "description" to description,
                    "type" to (legacyDocument.getString("type")?.takeIf { it.isNotBlank() } ?: ProjectItemType.IMPROVEMENT.value),
                    "done" to (legacyDocument.getBoolean("done") ?: false),
                    "createdAt" to (legacyDocument.get("createdAt") ?: FieldValue.serverTimestamp()),
                    "updatedAt" to (legacyDocument.get("updatedAt") ?: FieldValue.serverTimestamp())
                )

                batch.set(target, payload)
                batch.delete(legacyDocument.reference)
                batch.update(
                    projectsCollection(uid).document(projectId),
                    "updatedAt",
                    legacyDocument.get("updatedAt") ?: FieldValue.serverTimestamp()
                )
            }
        }.await()
    }

    private suspend fun touchProject(uid: String, projectId: String) {
        if (projectId.isBlank()) return
        projectsCollection(uid)
            .document(projectId)
            .update("updatedAt", FieldValue.serverTimestamp())
            .await()
    }

    private fun projectsCollection(uid: String) = firestore
        .collection("users")
        .document(uid)
        .collection("projects")

    private fun projectItemsCollection(uid: String, projectId: String): CollectionReference {
        return projectsCollection(uid)
            .document(projectId)
            .collection("items")
    }

    private fun legacyProjectItemsCollection(uid: String): CollectionReference {
        return firestore
            .collection("users")
            .document(uid)
            .collection("projectItems")
    }

    private fun DocumentSnapshot.toProjectOrNull(): Project? {
        val name = getString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Project(
            id = id,
            ownerId = getString("ownerId").orEmpty(),
            name = name,
            createdAt = get("createdAt") as? Timestamp,
            updatedAt = get("updatedAt") as? Timestamp
        )
    }

    private fun DocumentSnapshot.toProjectItemOrNull(): ProjectItem? {
        val legacyText = getString("text")?.trim()?.takeIf { it.isNotEmpty() }
        val title = getString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: legacyText ?: return null
        val projectId = getString("projectId").orEmpty().trim()
        if (projectId.isEmpty()) return null
        val description = getString("description")?.trim() ?: legacyText.orEmpty()
        val type = ProjectItemType.from(getString("type"))

        return ProjectItem(
            id = id,
            projectId = projectId,
            ownerId = getString("ownerId").orEmpty(),
            title = title,
            description = description,
            type = type,
            done = get("done") as? Boolean ?: false,
            createdAt = get("createdAt") as? Timestamp,
            updatedAt = get("updatedAt") as? Timestamp
        )
    }
}
