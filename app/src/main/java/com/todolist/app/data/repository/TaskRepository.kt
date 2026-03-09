package com.todolist.app.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskPatch
import com.todolist.app.data.model.TaskPriority
import com.todolist.app.data.model.TaskStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class TaskRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    fun getOpenTasks(uid: String): Flow<List<Task>> {
        return tasksFlow(uid) { task ->
            !task.archived && task.status != TaskStatus.DONE
        }
    }

    fun getArchivedTasks(uid: String): Flow<List<Task>> {
        return tasksFlow(uid) { task ->
            task.archived
        }
    }

    fun getDoneTasks(uid: String): Flow<List<Task>> {
        return tasksFlow(uid) { task ->
            !task.archived && task.status == TaskStatus.DONE
        }
    }

    suspend fun getOpenTasksForWidget(
        uid: String,
        selectedTags: Set<String>,
        maxItems: Int = 5
    ): List<Task> {
        val normalizedSelectedTags = selectedTags
            .map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        return tasksCollection(uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toTaskOrNull() }
            .filter { task ->
                !task.archived &&
                    task.status != TaskStatus.DONE &&
                    !task.completed
            }
            .filter { task ->
                if (normalizedSelectedTags.isEmpty()) {
                    true
                } else {
                    task.tags.any { it in normalizedSelectedTags }
                }
            }
            .sortedWith(
                compareByDescending<Task> { it.priority.rank }
                    .thenComparator { a, b -> compareDueDate(a.dueDate, b.dueDate) }
            )
            .take(maxItems.coerceAtLeast(1))
    }

    suspend fun addTask(
        uid: String,
        title: String,
        priority: TaskPriority,
        dueDate: Timestamp?,
        tags: List<String>
    ) {
        require(uid.isNotBlank()) { "UID invalido para crear tarea" }
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "El titulo no puede estar vacio" }

        val cleanTags = tags
            .map { normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .distinct()

        tasksCollection(uid).add(
            mapOf(
                "title" to cleanTitle,
                "completed" to false,
                "status" to TaskStatus.BACKLOG.value,
                "archived" to false,
                "priority" to priority.value,
                "dueDate" to dueDate,
                "tags" to cleanTags,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun updateTask(uid: String, taskId: String, patch: TaskPatch) {
        if (taskId.isBlank()) return

        val docRef = tasksCollection(uid).document(taskId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            if (!snapshot.exists()) return@runTransaction

            val current = snapshot.toTaskOrDefault()

            var nextCompleted = current.completed
            var nextStatus = current.status
            var nextArchived = current.archived
            var nextPriority = current.priority
            var nextDueDate = current.dueDate
            var nextTitle = current.title
            var nextTags = current.tags

            patch.status?.let {
                nextStatus = it
                if (it == TaskStatus.DONE) {
                    nextCompleted = true
                } else if (nextCompleted) {
                    nextCompleted = false
                }
            }

            if (patch.toggleCompleted) {
                nextCompleted = !nextCompleted
                nextStatus = if (nextCompleted) TaskStatus.DONE else TaskStatus.BACKLOG
            }

            if (patch.toggleArchived) {
                nextArchived = !nextArchived
            }

            patch.priority?.let {
                nextPriority = it
            }

            if (patch.setDueDate) {
                nextDueDate = patch.dueDate
            }
            patch.title?.let {
                nextTitle = it.trim()
            }
            patch.tags?.let {
                nextTags = it
                    .map { tag -> normalizeTag(tag) }
                    .filter { tag -> tag.isNotEmpty() }
                    .distinct()
            }

            if (nextCompleted != current.completed) {
                transaction.update(docRef, "completed", nextCompleted)
            }
            if (nextStatus != current.status) {
                transaction.update(docRef, "status", nextStatus.value)
            }
            if (nextArchived != current.archived) {
                transaction.update(docRef, "archived", nextArchived)
            }
            if (nextPriority != current.priority) {
                transaction.update(docRef, "priority", nextPriority.value)
            }
            if (patch.setDueDate && nextDueDate != current.dueDate) {
                transaction.update(docRef, "dueDate", nextDueDate)
            }
            if (patch.title != null && nextTitle.isNotBlank() && nextTitle != current.title) {
                transaction.update(docRef, "title", nextTitle)
            }
            if (patch.tags != null && nextTags != current.tags) {
                transaction.update(docRef, "tags", nextTags)
            }
        }.await()
    }

    suspend fun deleteTask(uid: String, taskId: String) {
        if (taskId.isBlank()) return
        tasksCollection(uid)
            .document(taskId)
            .delete()
            .await()
    }

    private fun tasksFlow(uid: String, predicate: (Task) -> Boolean): Flow<List<Task>> = callbackFlow {
        val listenerRegistration = tasksCollection(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                val tasks = snapshot?.documents
                    .orEmpty()
                    .mapNotNull { it.toTaskOrNull() }
                    .filter(predicate)
                    .sortedWith(
                        compareByDescending<Task> { it.priority.rank }
                            .thenComparator { a, b -> compareDueDate(a.dueDate, b.dueDate) }
                    )

                trySend(tasks).isSuccess
            }

        awaitClose { listenerRegistration.remove() }
    }

    private fun tasksCollection(uid: String) = firestore
        .collection("users")
        .document(uid)
        .collection("tasks")

    private fun DocumentSnapshot.toTaskOrNull(): Task? {
        val title = getString("title") ?: return null
        val rawTags = get("tags")
        val parsedTags = parseTagsRaw(rawTags)

        val mappedTask = Task(
            id = id,
            title = title,
            completed = getBoolean("completed") ?: false,
            status = TaskStatus.from(getString("status")),
            archived = getBoolean("archived") ?: false,
            priority = TaskPriority.from(getString("priority")),
            dueDate = getTimestamp("dueDate"),
            tags = parsedTags,
            createdAt = getTimestamp("createdAt")
        )

        val rawType = rawTags?.javaClass?.name ?: "null"
        val itemTypes = (rawTags as? List<*>)?.map { it?.javaClass?.name ?: "null" } ?: emptyList()
        Log.d(
            "TodoListTags",
            "map task id=${mappedTask.id} title=${mappedTask.title} tags=${mappedTask.tags} rawTagsType=$rawType rawItemTypes=$itemTypes"
        )

        return mappedTask
    }

    private fun DocumentSnapshot.toTaskOrDefault(): Task {
        return toTaskOrNull() ?: Task(id = id)
    }

    private fun compareDueDate(a: Timestamp?, b: Timestamp?): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1

        val seconds = a.seconds.compareTo(b.seconds)
        if (seconds != 0) return seconds
        return a.nanoseconds.compareTo(b.nanoseconds)
    }

    private fun parseTagsRaw(raw: Any?): List<String> {
        return when (raw) {
            is List<*> -> raw
                .filterIsInstance<String>()
                .map { normalizeTag(it) }
                .filter { it.isNotEmpty() }
                .distinct()
            is String -> raw
                .split(",", ";")
                .map { normalizeTag(it) }
                .filter { it.isNotEmpty() }
                .distinct()
            else -> emptyList()
        }
    }

    private fun normalizeTag(value: String): String {
        return value.trim().lowercase()
    }
}
