package com.todolist.app.data.repository

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

    suspend fun addTask(
        uid: String,
        title: String,
        priority: TaskPriority,
        dueDate: Timestamp?,
        tags: List<String>
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return

        val cleanTags = tags
            .map { it.trim() }
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
        val rawTags = get("tags") as? List<*> ?: emptyList<Any>()

        return Task(
            id = id,
            title = title,
            completed = getBoolean("completed") ?: false,
            status = TaskStatus.from(getString("status")),
            archived = getBoolean("archived") ?: false,
            priority = TaskPriority.from(getString("priority")),
            dueDate = getTimestamp("dueDate"),
            tags = rawTags.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() },
            createdAt = getTimestamp("createdAt")
        )
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
}
