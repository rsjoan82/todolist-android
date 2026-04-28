package com.todolist.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todolist.app.data.model.UserList
import com.todolist.app.data.model.UserListItem
import com.todolist.app.viewmodel.UserListSummary
import kotlinx.coroutines.launch

@Composable
fun UserListScreen(
    listSummaries: List<UserListSummary>,
    selectedListSummary: UserListSummary?,
    selectedListItems: List<UserListItem>,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onSelectList: (String?) -> Unit,
    onCreateList: (String) -> Unit,
    onRenameList: (UserList, String) -> Unit,
    onDeleteList: (UserList) -> Unit,
    onCreateListItem: (String, String) -> Unit,
    onEditListItem: (UserListItem, String) -> Unit,
    onDeleteListItem: (UserListItem) -> Unit,
    onToggleListItemChecked: (UserListItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var createListDialogOpen by remember { mutableStateOf(false) }
    var renameListTarget by remember { mutableStateOf<UserList?>(null) }
    var deleteListTarget by remember { mutableStateOf<UserList?>(null) }
    var listNameDraft by remember { mutableStateOf("") }

    var createItemDialogOpen by remember { mutableStateOf(false) }
    var editItemTarget by remember { mutableStateOf<UserListItem?>(null) }
    var deleteItemTarget by remember { mutableStateOf<UserListItem?>(null) }
    var itemTextDraft by remember { mutableStateOf("") }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedListSummary == null) {
                UserListListContent(
                    listSummaries = listSummaries,
                    isLoading = isLoading,
                    onOpenList = onSelectList,
                    onRenameList = { list ->
                        renameListTarget = list
                        listNameDraft = list.name
                    },
                    onDeleteList = { list ->
                        deleteListTarget = list
                    }
                )
            } else {
                UserListDetailContent(
                    summary = selectedListSummary,
                    items = selectedListItems,
                    isLoading = isLoading,
                    onBack = { onSelectList(null) },
                    onRenameList = {
                        renameListTarget = selectedListSummary.list
                        listNameDraft = selectedListSummary.list.name
                    },
                    onDeleteList = {
                        deleteListTarget = selectedListSummary.list
                    },
                    onToggleItemChecked = onToggleListItemChecked,
                    onEditItem = { item ->
                        editItemTarget = item
                        itemTextDraft = item.text
                    },
                    onDeleteItem = { item ->
                        deleteItemTarget = item
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = {
                if (selectedListSummary == null) {
                    listNameDraft = ""
                    createListDialogOpen = true
                } else {
                    itemTextDraft = ""
                    createItemDialogOpen = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = if (selectedListSummary == null) "Nueva lista" else "Nuevo item"
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        )
    }

    if (createListDialogOpen) {
        TextInputDialog(
            title = "Crear lista",
            label = "Nombre de la lista",
            value = listNameDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Crear",
            onValueChange = { listNameDraft = it },
            onDismiss = { if (!isSaving) createListDialogOpen = false },
            onConfirm = {
                onCreateList(listNameDraft)
                if (listNameDraft.trim().isNotEmpty()) {
                    createListDialogOpen = false
                }
            }
        )
    }

    renameListTarget?.let { list ->
        TextInputDialog(
            title = "Renombrar lista",
            label = "Nombre de la lista",
            value = listNameDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Guardar",
            onValueChange = { listNameDraft = it },
            onDismiss = { if (!isSaving) renameListTarget = null },
            onConfirm = {
                onRenameList(list, listNameDraft)
                if (listNameDraft.trim().isNotEmpty()) {
                    renameListTarget = null
                }
            }
        )
    }

    deleteListTarget?.let { list ->
        ConfirmDialog(
            title = "Borrar lista",
            text = "Se borrara \"${list.name}\" junto con todos sus items.",
            confirmLabel = if (isSaving) "Borrando..." else "Borrar",
            onDismiss = { if (!isSaving) deleteListTarget = null },
            onConfirm = {
                onDeleteList(list)
                deleteListTarget = null
            }
        )
    }

    if (createItemDialogOpen && selectedListSummary != null) {
        TextInputDialog(
            title = "Nuevo item",
            label = "Texto",
            value = itemTextDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Crear",
            onValueChange = { itemTextDraft = it },
            onDismiss = { if (!isSaving) createItemDialogOpen = false },
            onConfirm = {
                onCreateListItem(selectedListSummary.list.id, itemTextDraft)
                if (itemTextDraft.trim().isNotEmpty()) {
                    createItemDialogOpen = false
                }
            }
        )
    }

    editItemTarget?.let { item ->
        TextInputDialog(
            title = "Editar item",
            label = "Texto",
            value = itemTextDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Guardar",
            onValueChange = { itemTextDraft = it },
            onDismiss = { if (!isSaving) editItemTarget = null },
            onConfirm = {
                onEditListItem(item, itemTextDraft)
                if (itemTextDraft.trim().isNotEmpty()) {
                    editItemTarget = null
                }
            }
        )
    }

    deleteItemTarget?.let { item ->
        ConfirmDialog(
            title = "Borrar item",
            text = "Se borrara este item de la lista.",
            confirmLabel = if (isSaving) "Borrando..." else "Borrar",
            onDismiss = { if (!isSaving) deleteItemTarget = null },
            onConfirm = {
                onDeleteListItem(item)
                deleteItemTarget = null
            }
        )
    }
}

@Composable
private fun UserListListContent(
    listSummaries: List<UserListSummary>,
    isLoading: Boolean,
    onOpenList: (String) -> Unit,
    onRenameList: (UserList) -> Unit,
    onDeleteList: (UserList) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Listas",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (listSummaries.isEmpty() && !isLoading) {
            EmptyListState(
                title = "No hay listas",
                message = "Crea una lista para compras, viajes o cualquier grupo rapido de items."
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 84.dp)) {
            items(listSummaries, key = { it.list.id }) { summary ->
                UserListSummaryRow(
                    summary = summary,
                    onOpen = { onOpenList(summary.list.id) },
                    onRename = { onRenameList(summary.list) },
                    onDelete = { onDeleteList(summary.list) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun UserListDetailContent(
    summary: UserListSummary,
    items: List<UserListItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRenameList: () -> Unit,
    onDeleteList: () -> Unit,
    onToggleItemChecked: (UserListItem) -> Unit,
    onEditItem: (UserListItem) -> Unit,
    onDeleteItem: (UserListItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.list.name,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = summary.shortSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ListMenu(
                onRename = onRenameList,
                onDelete = onDeleteList
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (items.isEmpty() && !isLoading) {
            EmptyListState(
                title = "No hay items",
                message = "Anade el primer item para empezar esta lista."
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 84.dp)) {
            items(items, key = { it.id }) { item ->
                UserListItemRow(
                    item = item,
                    onToggleChecked = { onToggleItemChecked(item) },
                    onEdit = { onEditItem(item) },
                    onDelete = { onDeleteItem(item) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun UserListSummaryRow(
    summary: UserListSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.list.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.shortSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ListMenu(
            onRename = onRename,
            onDelete = onDelete
        )
    }
}

@Composable
private fun UserListItemRow(
    item: UserListItem,
    onToggleChecked: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { onToggleChecked() }
        )

        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None
        )

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Mas acciones")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.checked) "Marcar pendiente" else "Marcar hecho") },
                    onClick = {
                        menuExpanded = false
                        onToggleChecked()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Editar") },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Borrar") },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ListMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Mas acciones")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Renombrar") },
                onClick = {
                    menuExpanded = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("Borrar") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun EmptyListState(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.trim().isNotEmpty()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
