package com.todolist.app.data.model

import com.google.firebase.Timestamp

enum class TaskStatus(val value: String) {
    BACKLOG("backlog"),
    IN_PROGRESS("in_progress"),
    DONE("done");

    companion object {
        fun from(value: String?): TaskStatus {
            return entries.firstOrNull { it.value == value } ?: BACKLOG
        }
    }
}

enum class TaskPriority(val value: String, val rank: Int) {
    BAJO("bajo", 1),
    MEDIO("medio", 2),
    ALTO("alto", 3),
    URGENTE("urgente", 4);

    companion object {
        fun from(value: String?): TaskPriority {
            return entries.firstOrNull { it.value == value } ?: MEDIO
        }
    }
}

data class Task(
    val id: String = "",
    val title: String = "",
    val completed: Boolean = false,
    val status: TaskStatus = TaskStatus.BACKLOG,
    val archived: Boolean = false,
    val priority: TaskPriority = TaskPriority.MEDIO,
    val dueDate: Timestamp? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Timestamp? = null
)

data class TaskPatch(
    val priority: TaskPriority? = null,
    val dueDate: Timestamp? = null,
    val setDueDate: Boolean = false,
    val status: TaskStatus? = null,
    val toggleArchived: Boolean = false,
    val toggleCompleted: Boolean = false
)
