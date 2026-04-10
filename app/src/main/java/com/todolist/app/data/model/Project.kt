package com.todolist.app.data.model

import com.google.firebase.Timestamp

enum class ProjectStatus(val label: String) {
    PENDING("Pendiente"),
    COMPLETED("Completado")
}

enum class ProjectItemType(val value: String) {
    BUG("bug"),
    IMPROVEMENT("improvement");

    companion object {
        fun from(value: String?): ProjectItemType {
            return entries.firstOrNull { it.value == value } ?: IMPROVEMENT
        }
    }
}

data class Project(
    val id: String = "",
    val ownerId: String = "",
    val name: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class ProjectItem(
    val id: String = "",
    val projectId: String = "",
    val ownerId: String = "",
    val title: String = "",
    val description: String = "",
    val type: ProjectItemType = ProjectItemType.IMPROVEMENT,
    val done: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class ProjectItemPatch(
    val title: String? = null,
    val description: String? = null,
    val type: ProjectItemType? = null,
    val done: Boolean? = null
)
