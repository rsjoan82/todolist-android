package com.todolist.app.data.model

import com.google.firebase.Timestamp

data class UserList(
    val id: String = "",
    val ownerId: String = "",
    val name: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class UserListItem(
    val id: String = "",
    val listId: String = "",
    val ownerId: String = "",
    val text: String = "",
    val checked: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class UserListItemPatch(
    val text: String? = null,
    val checked: Boolean? = null
)
