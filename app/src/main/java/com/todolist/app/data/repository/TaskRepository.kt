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
    companion object {
        private const val HIDE_DONE_IN_WIDGET_AFTER_HOURS = 24L
    }

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

    suspend fun getAllActiveTasksForWidget(uid: String): List<Task> {
        return tasksCollection(uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toTaskOrNull() }
            .filter { !it.archived }
    }

    suspend fun archiveVisibleDoneTasksForWidget(uid: String): Int {
        val doneTasks = tasksCollection(uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toTaskOrNull() }
            .filter { task ->
                !task.archived &&
                    (task.status == TaskStatus.DONE || task.completed) &&
                    !shouldHideDoneTaskInWidget(task)
            }

        doneTasks.forEach { task ->
            tasksCollection(uid)
                .document(task.id)
                .update(
                    mapOf(
                        "archived" to true,
                        "archivedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        }

        return doneTasks.size
    }

    suspend fun getOpenTasksForWidget(
        uid: String,
        selectedTag: String?,
        maxItems: Int = 5
    ): List<Task> {
        return getTasksForWidget(
            uid = uid,
            selectedTag = selectedTag,
            includeDone = false,
            maxItems = maxItems
        )
    }

    suspend fun getDoneTasksForWidget(
        uid: String,
        selectedTag: String?,
        maxItems: Int = 5
    ): List<Task> {
        return getTasksForWidget(
            uid = uid,
            selectedTag = selectedTag,
            includeDone = true,
            maxItems = maxItems
        )
    }

    private suspend fun getTasksForWidget(
        uid: String,
        selectedTag: String?,
        includeDone: Boolean,
        maxItems: Int = 5
    ): List<Task> {
        val normalizedSelectedTag = normalizeTagId(selectedTag.orEmpty()).ifEmpty { null }

        return tasksCollection(uid)
            .get()
            .await()
            .documents
            .mapNotNull { it.toTaskOrNull() }
            .filter { task ->
                if (includeDone) {
                    !task.archived &&
                        (task.status == TaskStatus.DONE || task.completed)
                } else {
                    !task.archived &&
                        task.status != TaskStatus.DONE &&
                        !task.completed
                }
            }
            .filter { task ->
                if (normalizedSelectedTag == null) {
                    true
                } else {
                    task.tagId == normalizedSelectedTag
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
        tagId: String?
    ) {
        require(uid.isNotBlank()) { "UID invalido para crear tarea" }
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "El titulo no puede estar vacio" }

        val cleanTagId = normalizeTagId(tagId.orEmpty()).ifEmpty { null }

        tasksCollection(uid).add(
            mapOf(
                "ownerId" to uid,
                "title" to cleanTitle,
                "completed" to false,
                "status" to "open",
                "boardColumn" to "backlog",
                "archived" to false,
                "priority" to priority.toFirestorePriority(),
                "dueDate" to dueDate,
                "tagId" to cleanTagId,
                "doneAt" to null,
                "archivedAt" to null,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
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
            var nextTagId = current.tagId
            var nextDoneAt = current.doneAt
            var nextFirestoreStatus = current.status.toFirestoreStatus()
            var nextBoardColumn = current.status.toFirestoreBoardColumn()
            var archivedChanged = false
            var hasChanges = false

            patch.status?.let {
                nextStatus = it
                if (it == TaskStatus.DONE) {
                    nextCompleted = true
                    nextDoneAt = current.doneAt ?: Timestamp.now()
                } else if (nextCompleted) {
                    nextCompleted = false
                    nextDoneAt = null
                }
                nextFirestoreStatus = nextStatus.toFirestoreStatus()
                nextBoardColumn = nextStatus.toFirestoreBoardColumn()
            }

            if (patch.toggleCompleted) {
                nextCompleted = !nextCompleted
                nextStatus = if (nextCompleted) TaskStatus.DONE else TaskStatus.BACKLOG
                nextDoneAt = if (nextCompleted) Timestamp.now() else null
                nextFirestoreStatus = nextStatus.toFirestoreStatus()
                nextBoardColumn = nextStatus.toFirestoreBoardColumn()
            }

            if (patch.toggleArchived) {
                nextArchived = !nextArchived
                archivedChanged = true
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
            if (patch.setTagId) {
                nextTagId = normalizeTagId(patch.tagId.orEmpty()).ifEmpty { null }
            }

            if (nextCompleted != current.completed) {
                transaction.update(docRef, "completed", nextCompleted)
                hasChanges = true
            }
            if (nextStatus != current.status || nextFirestoreStatus != current.status.toFirestoreStatus()) {
                transaction.update(docRef, "status", nextFirestoreStatus)
                transaction.update(docRef, "boardColumn", nextBoardColumn)
                hasChanges = true
            }
            if (!sameTimestamp(nextDoneAt, current.doneAt)) {
                transaction.update(docRef, "doneAt", nextDoneAt)
                hasChanges = true
            }
            if (nextArchived != current.archived) {
                transaction.update(docRef, "archived", nextArchived)
                hasChanges = true
            }
            if (nextPriority != current.priority) {
                transaction.update(docRef, "priority", nextPriority.toFirestorePriority())
                hasChanges = true
            }
            if (patch.setDueDate && nextDueDate != current.dueDate) {
                transaction.update(docRef, "dueDate", nextDueDate)
                hasChanges = true
            }
            if (patch.title != null && nextTitle.isNotBlank() && nextTitle != current.title) {
                transaction.update(docRef, "title", nextTitle)
                hasChanges = true
            }
            if (patch.setTagId && nextTagId != current.tagId) {
                transaction.update(
                    docRef,
                    mapOf(
                        "tagId" to nextTagId,
                        "tag" to FieldValue.delete(),
                        "tags" to FieldValue.delete(),
                        "tagIds" to FieldValue.delete()
                    )
                )
                hasChanges = true
            }
            if (archivedChanged) {
                transaction.update(
                    docRef,
                    "archivedAt",
                    if (nextArchived) FieldValue.serverTimestamp() else null
                )
                hasChanges = true
            }
            if (hasChanges) {
                transaction.update(docRef, "updatedAt", FieldValue.serverTimestamp())
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
        val title = parseTitleRaw(get("title")) ?: return null
        val rawTagId = get("tagId")
        val rawTag = get("tag")
        val rawTags = get("tags")
        val rawTagIds = get("tagIds")
        val parsedTagId = parseTagRaw(rawTagId, rawTag, rawTagIds, rawTags)

        val mappedTask = Task(
            id = id,
            title = title,
            completed = parseBooleanRaw(get("completed")),
            status = parseStatusRaw(get("status")),
            archived = parseBooleanRaw(get("archived")),
            priority = parsePriorityRaw(get("priority")),
            dueDate = parseTimestampRaw(get("dueDate")),
            tagId = parsedTagId,
            doneAt = parseTimestampRaw(get("doneAt")),
            createdAt = parseTimestampRaw(get("createdAt"))
        )

        val rawTagIdType = rawTagId?.javaClass?.name ?: "null"
        val rawType = rawTag?.javaClass?.name ?: "null"
        val legacyType = rawTags?.javaClass?.name ?: "null"
        val legacyTagIdsType = rawTagIds?.javaClass?.name ?: "null"
        Log.d(
            "TodoListTags",
            "map task id=${mappedTask.id} title=${mappedTask.title} tagId=${mappedTask.tagId} rawTagIdType=$rawTagIdType rawTagType=$rawType legacyTagsType=$legacyType legacyTagIdsType=$legacyTagIdsType"
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

    private fun sameTimestamp(a: Timestamp?, b: Timestamp?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.seconds == b.seconds && a.nanoseconds == b.nanoseconds
    }

    fun shouldHideDoneTaskInWidget(task: Task, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (task.archived || (task.status != TaskStatus.DONE && !task.completed)) return false

        val doneAt = task.doneAt ?: return false
        val doneAtMillis = doneAt.seconds * 1000L + doneAt.nanoseconds / 1_000_000L
        val hideAfterMillis = HIDE_DONE_IN_WIDGET_AFTER_HOURS * 60L * 60L * 1000L
        return nowMillis - doneAtMillis >= hideAfterMillis
    }

    private fun parseTagRaw(rawTagId: Any?, rawTag: Any?, rawLegacyTagIds: Any?, rawLegacyTags: Any?): String? {
        val directTagId = when (rawTagId) {
            is String -> normalizeTagId(rawTagId)
            else -> ""
        }
        if (directTagId.isNotEmpty()) return directTagId

        val directTag = when (rawTag) {
            is String -> normalizeTagId(rawTag)
            else -> ""
        }
        if (directTag.isNotEmpty()) return directTag

        val legacyTagId = when (rawLegacyTagIds) {
            is List<*> -> rawLegacyTagIds
                .filterIsInstance<String>()
                .map { normalizeTagId(it) }
                .firstOrNull { it.isNotEmpty() }
            is String -> rawLegacyTagIds
                .split(",", ";")
                .map { normalizeTagId(it) }
                .firstOrNull { it.isNotEmpty() }
            else -> null
        }
        if (legacyTagId != null) return legacyTagId

        return when (rawLegacyTags) {
            is List<*> -> rawLegacyTags
                .filterIsInstance<String>()
                .map { normalizeTagId(it) }
                .firstOrNull { it.isNotEmpty() }
            is String -> rawLegacyTags
                .split(",", ";")
                .map { normalizeTagId(it) }
                .firstOrNull { it.isNotEmpty() }
            else -> null
        }
    }

    private fun parseTitleRaw(rawTitle: Any?): String? {
        return (rawTitle as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseBooleanRaw(rawValue: Any?): Boolean {
        return when (rawValue) {
            is Boolean -> rawValue
            is Number -> rawValue.toInt() != 0
            is String -> {
                when (rawValue.trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun parseStatusRaw(rawStatus: Any?): TaskStatus {
        return when (rawStatus) {
            is String -> TaskStatus.from(rawStatus)
            is Number -> {
                when (rawStatus.toInt()) {
                    2 -> TaskStatus.DONE
                    1 -> TaskStatus.IN_PROGRESS
                    else -> TaskStatus.BACKLOG
                }
            }
            else -> TaskStatus.BACKLOG
        }
    }

    private fun parsePriorityRaw(rawPriority: Any?): TaskPriority {
        return when (rawPriority) {
            is String -> TaskPriority.from(rawPriority)
            is Number -> {
                when (rawPriority.toInt()) {
                    3, 4 -> TaskPriority.URGENTE
                    2 -> TaskPriority.ALTO
                    1 -> TaskPriority.MEDIO
                    0 -> TaskPriority.BAJO
                    else -> TaskPriority.MEDIO
                }
            }
            else -> TaskPriority.MEDIO
        }
    }

    private fun parseTimestampRaw(rawValue: Any?): Timestamp? {
        return rawValue as? Timestamp
    }

    private fun normalizeTagId(value: String): String {
        return value.trim()
    }

    private fun TaskPriority.toFirestorePriority(): Int {
        return when (this) {
            TaskPriority.BAJO -> 0
            TaskPriority.MEDIO -> 1
            TaskPriority.ALTO -> 2
            TaskPriority.URGENTE -> 3
        }
    }

    private fun TaskStatus.toFirestoreStatus(): String {
        return when (this) {
            TaskStatus.DONE -> "done"
            TaskStatus.BACKLOG, TaskStatus.IN_PROGRESS -> "open"
        }
    }

    private fun TaskStatus.toFirestoreBoardColumn(): String {
        return when (this) {
            TaskStatus.BACKLOG -> "backlog"
            TaskStatus.IN_PROGRESS -> "in_progress"
            TaskStatus.DONE -> "done"
        }
    }
}
