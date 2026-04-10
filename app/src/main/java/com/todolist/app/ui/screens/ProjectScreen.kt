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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.todolist.app.data.model.Project
import com.todolist.app.data.model.ProjectItem
import com.todolist.app.data.model.ProjectItemType
import com.todolist.app.data.model.ProjectStatus
import com.todolist.app.viewmodel.ProjectSummary
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProjectScreen(
    projectSummaries: List<ProjectSummary>,
    selectedProjectSummary: ProjectSummary?,
    selectedProjectItems: List<ProjectItem>,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onSelectProject: (String?) -> Unit,
    onCreateProject: (String) -> Unit,
    onRenameProject: (Project, String) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onCreateProjectItem: (String, String, String, ProjectItemType) -> Unit,
    onEditProjectItem: (ProjectItem, String, String, ProjectItemType) -> Unit,
    onDeleteProjectItem: (ProjectItem) -> Unit,
    onToggleProjectItemDone: (ProjectItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var createProjectDialogOpen by remember { mutableStateOf(false) }
    var renameProjectTarget by remember { mutableStateOf<Project?>(null) }
    var deleteProjectTarget by remember { mutableStateOf<Project?>(null) }
    var projectNameDraft by remember { mutableStateOf("") }

    var createItemDialogOpen by remember { mutableStateOf(false) }
    var editItemTarget by remember { mutableStateOf<ProjectItem?>(null) }
    var deleteItemTarget by remember { mutableStateOf<ProjectItem?>(null) }
    var itemTitleDraft by remember { mutableStateOf("") }
    var itemDescriptionDraft by remember { mutableStateOf("") }
    var itemTypeDraft by remember { mutableStateOf(ProjectItemType.IMPROVEMENT) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedProjectSummary == null) {
                ProjectListContent(
                    projectSummaries = projectSummaries,
                    isLoading = isLoading,
                    onOpenProject = onSelectProject,
                    onRenameProject = { project ->
                        renameProjectTarget = project
                        projectNameDraft = project.name
                    },
                    onDeleteProject = { project ->
                        deleteProjectTarget = project
                    }
                )
            } else {
                ProjectDetailContent(
                    summary = selectedProjectSummary,
                    items = selectedProjectItems,
                    isLoading = isLoading,
                    onBack = { onSelectProject(null) },
                    onRenameProject = {
                        renameProjectTarget = selectedProjectSummary.project
                        projectNameDraft = selectedProjectSummary.project.name
                    },
                    onDeleteProject = {
                        deleteProjectTarget = selectedProjectSummary.project
                    },
                    onToggleItemDone = onToggleProjectItemDone,
                    onEditItem = { item ->
                        editItemTarget = item
                        itemTitleDraft = item.title
                        itemDescriptionDraft = item.description
                        itemTypeDraft = item.type
                    },
                    onDeleteItem = { item ->
                        deleteItemTarget = item
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = {
                if (selectedProjectSummary == null) {
                    projectNameDraft = ""
                    createProjectDialogOpen = true
                } else {
                    itemTitleDraft = ""
                    itemDescriptionDraft = ""
                    itemTypeDraft = ProjectItemType.IMPROVEMENT
                    createItemDialogOpen = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = if (selectedProjectSummary == null) "Nuevo proyecto" else "Nuevo item"
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        )
    }

    if (createProjectDialogOpen) {
        TextInputDialog(
            title = "Crear proyecto",
            label = "Nombre del proyecto",
            value = projectNameDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Crear",
            onValueChange = { projectNameDraft = it },
            onDismiss = { if (!isSaving) createProjectDialogOpen = false },
            onConfirm = {
                onCreateProject(projectNameDraft)
                if (projectNameDraft.trim().isNotEmpty()) {
                    createProjectDialogOpen = false
                }
            }
        )
    }

    renameProjectTarget?.let { project ->
        TextInputDialog(
            title = "Renombrar proyecto",
            label = "Nombre del proyecto",
            value = projectNameDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Guardar",
            onValueChange = { projectNameDraft = it },
            onDismiss = { if (!isSaving) renameProjectTarget = null },
            onConfirm = {
                onRenameProject(project, projectNameDraft)
                if (projectNameDraft.trim().isNotEmpty()) {
                    renameProjectTarget = null
                }
            }
        )
    }

    deleteProjectTarget?.let { project ->
        ConfirmDialog(
            title = "Borrar proyecto",
            text = "Se borrara \"${project.name}\" junto con todos sus items.",
            confirmLabel = if (isSaving) "Borrando..." else "Borrar",
            onDismiss = { if (!isSaving) deleteProjectTarget = null },
            onConfirm = {
                onDeleteProject(project)
                deleteProjectTarget = null
            }
        )
    }

    if (createItemDialogOpen && selectedProjectSummary != null) {
        ProjectItemEditorDialog(
            title = "Nuevo item",
            itemTitle = itemTitleDraft,
            itemDescription = itemDescriptionDraft,
            itemType = itemTypeDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Crear",
            onTitleChange = { itemTitleDraft = it },
            onDescriptionChange = { itemDescriptionDraft = it },
            onTypeChange = { itemTypeDraft = it },
            onDismiss = { if (!isSaving) createItemDialogOpen = false },
            onConfirm = {
                onCreateProjectItem(
                    selectedProjectSummary.project.id,
                    itemTitleDraft,
                    itemDescriptionDraft,
                    itemTypeDraft
                )
                if (itemTitleDraft.trim().isNotEmpty()) {
                    createItemDialogOpen = false
                }
            }
        )
    }

    editItemTarget?.let { item ->
        ProjectItemEditorDialog(
            title = "Editar item",
            itemTitle = itemTitleDraft,
            itemDescription = itemDescriptionDraft,
            itemType = itemTypeDraft,
            confirmLabel = if (isSaving) "Guardando..." else "Guardar",
            onTitleChange = { itemTitleDraft = it },
            onDescriptionChange = { itemDescriptionDraft = it },
            onTypeChange = { itemTypeDraft = it },
            onDismiss = { if (!isSaving) editItemTarget = null },
            onConfirm = {
                onEditProjectItem(item, itemTitleDraft, itemDescriptionDraft, itemTypeDraft)
                if (itemTitleDraft.trim().isNotEmpty()) {
                    editItemTarget = null
                }
            }
        )
    }

    deleteItemTarget?.let { item ->
        ConfirmDialog(
            title = "Borrar item",
            text = "Se borrara este item del proyecto.",
            confirmLabel = if (isSaving) "Borrando..." else "Borrar",
            onDismiss = { if (!isSaving) deleteItemTarget = null },
            onConfirm = {
                onDeleteProjectItem(item)
                deleteItemTarget = null
            }
        )
    }
}

@Composable
private fun ProjectListContent(
    projectSummaries: List<ProjectSummary>,
    isLoading: Boolean,
    onOpenProject: (String) -> Unit,
    onRenameProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Proyectos",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (projectSummaries.isEmpty() && !isLoading) {
            EmptyProjectState(
                title = "No hay proyectos",
                message = "Crea tu primer proyecto para separar notas, mejoras e items de tus tareas personales."
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 84.dp)) {
            items(projectSummaries, key = { it.project.id }) { summary ->
                ProjectSummaryRow(
                    summary = summary,
                    onOpen = { onOpenProject(summary.project.id) },
                    onRename = { onRenameProject(summary.project) },
                    onDelete = { onDeleteProject(summary.project) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProjectDetailContent(
    summary: ProjectSummary,
    items: List<ProjectItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRenameProject: () -> Unit,
    onDeleteProject: () -> Unit,
    onToggleItemDone: (ProjectItem) -> Unit,
    onEditItem: (ProjectItem) -> Unit,
    onDeleteItem: (ProjectItem) -> Unit
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
                    text = summary.project.name,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${summary.status.label} · ${summary.shortSummary}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor(summary.status)
                )
            }
            ProjectMenu(
                onRename = onRenameProject,
                onDelete = onDeleteProject
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (items.isEmpty() && !isLoading) {
            EmptyProjectState(
                title = "No hay items en este proyecto",
                message = "Anade notas, tareas o mejoras para seguir el estado automaticamente."
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 84.dp)) {
            items(items, key = { it.id }) { item ->
                ProjectItemRow(
                    item = item,
                    onToggleDone = { onToggleItemDone(item) },
                    onEdit = { onEditItem(item) },
                    onDelete = { onDeleteItem(item) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProjectSummaryRow(
    summary: ProjectSummary,
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
                text = summary.project.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.status.label,
                color = statusColor(summary.status),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = summary.shortSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ProjectMenu(
            onRename = onRename,
            onDelete = onDelete
        )
    }
}

@Composable
private fun ProjectItemRow(
    item: ProjectItem,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleDone) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = if (item.done) "Marcar pendiente" else "Marcar hecho",
                tint = if (item.done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Text(
            text = item.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
        )

        Icon(
            imageVector = if (item.type == ProjectItemType.BUG) Icons.Filled.BugReport else Icons.Filled.Star,
            contentDescription = if (item.type == ProjectItemType.BUG) "Bug" else "Mejora",
            tint = if (item.type == ProjectItemType.BUG) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(end = 4.dp)
        )

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Mas acciones")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.done) "Marcar pendiente" else "Marcar hecho") },
                    onClick = {
                        menuExpanded = false
                        onToggleDone()
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
private fun ProjectMenu(
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
private fun EmptyProjectState(
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
                singleLine = false
            )
        },
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

@Composable
private fun ProjectItemEditorDialog(
    title: String,
    itemTitle: String,
    itemDescription: String,
    itemType: ProjectItemType,
    confirmLabel: String,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTypeChange: (ProjectItemType) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Define un titulo claro y deja el contenido largo en la descripcion.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Titulo",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = itemTitle,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ej. Mejorar filtros de la pantalla") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        supportingText = {
                            Text("Este titulo es el que se mostrara en la lista.")
                        }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Tipo",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = itemType == ProjectItemType.BUG,
                            onClick = { onTypeChange(ProjectItemType.BUG) },
                            label = { Text("Bug") },
                            leadingIcon = {
                                Icon(Icons.Filled.BugReport, contentDescription = null)
                            }
                        )
                        FilterChip(
                            selected = itemType == ProjectItemType.IMPROVEMENT,
                            onClick = { onTypeChange(ProjectItemType.IMPROVEMENT) },
                            label = { Text("Mejora") },
                            leadingIcon = {
                                Icon(Icons.Filled.Star, contentDescription = null)
                            }
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Descripcion",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = itemDescription,
                        onValueChange = onDescriptionChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
                        placeholder = { Text("Anade contexto, notas, detalles tecnicos o siguientes pasos...") },
                        minLines = 8,
                        maxLines = 14,
                        supportingText = {
                            Text("Pensado para texto largo y notas de trabajo.")
                        }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = itemTitle.trim().isNotEmpty()
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
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

@Composable
private fun statusColor(status: ProjectStatus) = when (status) {
    ProjectStatus.PENDING -> MaterialTheme.colorScheme.primary
    ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
}
