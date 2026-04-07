package com.todolist.app.ui.screens

import android.app.DatePickerDialog
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
import com.todolist.app.data.model.Tag
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskPriority
import com.todolist.app.ui.TaskFilterPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TaskTab { OPEN, DONE, ARCHIVED }
private const val TAG_LOG = "TodoListTags"
private const val TAG_UNDO = "TodoListUndo"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TaskScreen(
    tags: List<Tag>,
    openTasks: List<Task>,
    doneTasks: List<Task>,
    archivedTasks: List<Task>,
    isLoading: Boolean,
    errorMessage: String?,
    onAddTask: (
        title: String,
        priority: TaskPriority,
        dueDate: Timestamp?,
        tagId: String?,
        onResult: (Result<Unit>) -> Unit
    ) -> Unit,
    onCreateTag: (
        name: String,
        onResult: (Result<Tag>) -> Unit
    ) -> Unit,
    onRenameTag: (
        tag: Tag,
        name: String,
        onResult: (Result<Unit>) -> Unit
    ) -> Unit,
    onDeleteTag: (
        tag: Tag,
        onResult: (Result<Unit>) -> Unit
    ) -> Unit,
    isCreating: Boolean,
    createError: String?,
    onClearCreateError: () -> Unit,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onSaveTask: (task: Task, title: String, priority: TaskPriority, dueDate: Timestamp?, tagId: String?) -> Unit,
    hasSession: Boolean,
    openFilterRequest: Int = 0,
    openCreateRequest: Int = 0,
    openTaskRequest: Int = 0,
    openTaskId: String? = null,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(TaskTab.OPEN) }

    var showCreateSheet by remember { mutableStateOf(false) }
    var createTitle by remember { mutableStateOf("") }
    var createSelectedTagId by remember { mutableStateOf<String?>(null) }
    var createPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
    var createDueDate by remember { mutableStateOf<Timestamp?>(null) }

    var editingTask by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editSelectedTagId by remember { mutableStateOf<String?>(null) }
    var editPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
    var editDueDate by remember { mutableStateOf<Timestamp?>(null) }
    var showTagFilterSheet by remember { mutableStateOf(false) }
    var handledOpenTaskRequest by remember { mutableStateOf(0) }
    val context = LocalContext.current
    var selectedTag by remember { mutableStateOf(TaskFilterPreferences.getSelectedTag(context)) }
    var draftSelectedTag by remember { mutableStateOf(selectedTag) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lastArchivedTask by remember { mutableStateOf<Task?>(null) }

    val onArchiveWithUndo: (Task) -> Unit = { task ->
        onToggleArchived(task)
        lastArchivedTask = task
        Log.d(TAG_UNDO, "Archived task id=${task.id}")
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Tarea archivada",
                actionLabel = "Deshacer",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && lastArchivedTask?.id == task.id) {
                onToggleArchived(task)
                Log.d(TAG_UNDO, "Undo archive id=${task.id}")
                lastArchivedTask = null
            } else if (lastArchivedTask?.id == task.id) {
                Log.d(TAG_UNDO, "Archive kept id=${task.id}")
                lastArchivedTask = null
            }
        }
    }

    val filteredOpenTasks = remember(openTasks, selectedTag) {
        applyFilters(openTasks, selectedTag)
    }
    val filteredDoneTasks = remember(doneTasks, selectedTag) {
        applyFilters(doneTasks, selectedTag)
    }
    val filteredArchivedTasks = remember(archivedTasks, selectedTag) {
        applyFilters(archivedTasks, selectedTag)
    }

    LaunchedEffect(tags, selectedTag) {
        if (selectedTag != null && tags.none { it.id == selectedTag }) {
            selectedTag = null
            draftSelectedTag = null
            TaskFilterPreferences.saveSelectedTag(context, null)
        }
    }

    LaunchedEffect(openFilterRequest) {
        if (openFilterRequest > 0) {
            draftSelectedTag = selectedTag
            showTagFilterSheet = true
        }
    }

    LaunchedEffect(openCreateRequest) {
        if (openCreateRequest > 0) {
            createTitle = ""
            createSelectedTagId = null
            createPriority = TaskPriority.MEDIO
            createDueDate = null
            onClearCreateError()
            showCreateSheet = true
        }
    }

    LaunchedEffect(openTaskRequest, openTaskId, openTasks, doneTasks, archivedTasks) {
        if (openTaskRequest <= handledOpenTaskRequest) return@LaunchedEffect

        val requestedTaskId = openTaskId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val taskToOpen = (openTasks + doneTasks + archivedTasks).firstOrNull { it.id == requestedTaskId }
            ?: return@LaunchedEffect

        editingTask = taskToOpen
        editTitle = taskToOpen.title
        editSelectedTagId = taskToOpen.tagId
        editPriority = taskToOpen.priority
        editDueDate = taskToOpen.dueDate
        handledOpenTaskRequest = openTaskRequest
    }

    val openCreateSheet = {
        createTitle = ""
        createSelectedTagId = null
        createPriority = TaskPriority.MEDIO
        createDueDate = null
        onClearCreateError()
        showCreateSheet = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(4.dp))

            val tabIndex = when (selectedTab) {
                TaskTab.OPEN -> 0
                TaskTab.DONE -> 1
                TaskTab.ARCHIVED -> 2
            }
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = selectedTab == TaskTab.OPEN,
                    onClick = { selectedTab = TaskTab.OPEN },
                    text = { Text("Abiertas (${filteredOpenTasks.size})") }
                )
                Tab(
                    selected = selectedTab == TaskTab.DONE,
                    onClick = { selectedTab = TaskTab.DONE },
                    text = { Text("Hechas (${filteredDoneTasks.size})") }
                )
                Tab(
                    selected = selectedTab == TaskTab.ARCHIVED,
                    onClick = { selectedTab = TaskTab.ARCHIVED },
                    text = { Text("Archivadas (${filteredArchivedTasks.size})") }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(4.dp))
            }

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (selectedTab == TaskTab.OPEN) {
                if (filteredOpenTasks.isEmpty()) {
                    EmptyTabState(
                        message = "No hay tareas abiertas",
                        buttonText = "Crear tarea",
                        onButtonClick = openCreateSheet
                    )
                } else {
                    TaskList(
                        tasks = filteredOpenTasks,
                        showArchiveAsUnarchive = false,
                        onToggleCompleted = onToggleCompleted,
                        onChangePriority = onChangePriority,
                        onToggleArchived = onToggleArchived,
                        onArchiveWithUndo = onArchiveWithUndo,
                        onDeleteTask = onDeleteTask,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        onEditTask = { task ->
                            editingTask = task
                            editTitle = task.title
                            editSelectedTagId = task.tagId
                            editPriority = task.priority
                            editDueDate = task.dueDate
                        }
                    )
                }
            } else if (selectedTab == TaskTab.DONE) {
                if (filteredDoneTasks.isEmpty()) {
                    EmptyTabState(message = "No hay tareas hechas")
                } else {
                    TaskList(
                        tasks = filteredDoneTasks,
                        showArchiveAsUnarchive = false,
                        onToggleCompleted = onToggleCompleted,
                        onChangePriority = onChangePriority,
                        onToggleArchived = onToggleArchived,
                        onArchiveWithUndo = onArchiveWithUndo,
                        onDeleteTask = onDeleteTask,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        onEditTask = { task ->
                            editingTask = task
                            editTitle = task.title
                            editSelectedTagId = task.tagId
                            editPriority = task.priority
                            editDueDate = task.dueDate
                        }
                    )
                }
            } else {
                if (filteredArchivedTasks.isEmpty()) {
                    EmptyTabState(message = "No hay tareas archivadas")
                } else {
                    TaskList(
                        tasks = filteredArchivedTasks,
                        showArchiveAsUnarchive = true,
                        onToggleCompleted = onToggleCompleted,
                        onChangePriority = onChangePriority,
                        onToggleArchived = onToggleArchived,
                        onArchiveWithUndo = onArchiveWithUndo,
                        onDeleteTask = onDeleteTask,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        onEditTask = { task ->
                            editingTask = task
                            editTitle = task.title
                            editSelectedTagId = task.tagId
                            editPriority = task.priority
                            editDueDate = task.dueDate
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = openCreateSheet,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Nueva tarea")
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        )
    }

    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
            NewTaskSheetContent(
                tags = tags,
                title = createTitle,
                onTitleChange = { createTitle = it },
                selectedTagId = createSelectedTagId,
                onSelectedTagIdChange = { createSelectedTagId = it },
                onCreateTag = onCreateTag,
                onRenameTag = onRenameTag,
                onDeleteTag = onDeleteTag,
                selectedPriority = createPriority,
                onPrioritySelected = { createPriority = it },
                selectedDueDate = createDueDate,
                onDueDateSelected = { createDueDate = it },
                onClearDueDate = { createDueDate = null },
                onCreate = {
                    if (!hasSession) {
                        scope.launch {
                            snackbarHostState.showSnackbar("No hay sesion. Abre login.")
                        }
                    } else {
                        onAddTask(
                            createTitle,
                            createPriority,
                            createDueDate,
                            createSelectedTagId
                        ) { result ->
                            result.onSuccess {
                                showCreateSheet = false
                                createTitle = ""
                                createSelectedTagId = null
                                createPriority = TaskPriority.MEDIO
                                createDueDate = null
                            }.onFailure { error ->
                                val message = error.message ?: "No se pudo crear la tarea"
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            }
                        }
                    }
                },
                onCancel = { showCreateSheet = false },
                hasSession = hasSession,
                createError = createError,
                isCreating = isCreating
            )
        }
    }

    if (showTagFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showTagFilterSheet = false }) {
            TagFilterSheet(
                availableTags = tags,
                selectedTag = draftSelectedTag,
                onSelectTag = { tag ->
                    draftSelectedTag = if (draftSelectedTag == tag) null else tag
                },
                onClear = { draftSelectedTag = null },
                onApply = {
                    selectedTag = draftSelectedTag
                    TaskFilterPreferences.saveSelectedTag(context, selectedTag)
                    showTagFilterSheet = false
                }
            )
        }
    }

    editingTask?.let { task ->
        ModalBottomSheet(onDismissRequest = { editingTask = null }) {
            EditTaskSheetContent(
                tags = tags,
                title = editTitle,
                onTitleChange = { editTitle = it },
                selectedTagId = editSelectedTagId,
                onSelectedTagIdChange = { editSelectedTagId = it },
                onCreateTag = onCreateTag,
                onRenameTag = onRenameTag,
                onDeleteTag = onDeleteTag,
                selectedPriority = editPriority,
                onPrioritySelected = { editPriority = it },
                selectedDueDate = editDueDate,
                onDueDateSelected = { editDueDate = it },
                onClearDueDate = { editDueDate = null },
                onSave = {
                    onSaveTask(task, editTitle, editPriority, editDueDate, editSelectedTagId)
                    editingTask = null
                },
                onCancel = { editingTask = null },
                onDelete = {
                    onDeleteTask(task)
                    editingTask = null
                }
            )
        }
    }
}

@Composable
private fun EmptyTabState(
    message: String,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (buttonText != null && onButtonClick != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onButtonClick) {
                Text(buttonText)
            }
        }
    }
}

@Composable
fun NewTaskSheetContent(
    tags: List<Tag>,
    title: String,
    onTitleChange: (String) -> Unit,
    selectedTagId: String?,
    onSelectedTagIdChange: (String?) -> Unit,
    onCreateTag: (String, (Result<Tag>) -> Unit) -> Unit,
    onRenameTag: (Tag, String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteTag: (Tag, (Result<Unit>) -> Unit) -> Unit,
    selectedPriority: TaskPriority,
    onPrioritySelected: (TaskPriority) -> Unit,
    selectedDueDate: Timestamp?,
    onDueDateSelected: (Timestamp?) -> Unit,
    onClearDueDate: () -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    hasSession: Boolean,
    createError: String?,
    isCreating: Boolean
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Nueva tarea", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            enabled = !isCreating,
            label = { Text("Titulo") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrioritySelector(
                selected = selectedPriority,
                onSelected = onPrioritySelected,
                modifier = Modifier.weight(1f),
                enabled = !isCreating
            )
            DueDateSelector(
                selectedDueDate = selectedDueDate,
                onDueDateSelected = onDueDateSelected,
                onClearDueDate = onClearDueDate,
                modifier = Modifier.weight(1f),
                enabled = !isCreating
            )
        }

        TagSelector(
            tags = tags,
            selectedTagId = selectedTagId,
            onSelectedTagIdChange = onSelectedTagIdChange,
            onCreateTag = onCreateTag,
            onRenameTag = onRenameTag,
            onDeleteTag = onDeleteTag,
            enabled = !isCreating
        )

        if (!hasSession) {
            Text("No hay sesion. Abre login.", color = MaterialTheme.colorScheme.error)
        }
        createError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !isCreating) {
                Text("Cancelar")
            }
            Button(
                onClick = onCreate,
                modifier = Modifier.weight(1f),
                enabled = title.isNotBlank() && hasSession && !isCreating
            ) {
                Text(if (isCreating) "Creando..." else "Crear")
            }
        }
    }
}

@Composable
private fun EditTaskSheetContent(
    tags: List<Tag>,
    title: String,
    onTitleChange: (String) -> Unit,
    selectedTagId: String?,
    onSelectedTagIdChange: (String?) -> Unit,
    onCreateTag: (String, (Result<Tag>) -> Unit) -> Unit,
    onRenameTag: (Tag, String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteTag: (Tag, (Result<Unit>) -> Unit) -> Unit,
    selectedPriority: TaskPriority,
    onPrioritySelected: (TaskPriority) -> Unit,
    selectedDueDate: Timestamp?,
    onDueDateSelected: (Timestamp?) -> Unit,
    onClearDueDate: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Borrar tarea")
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Titulo") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrioritySelector(
                selected = selectedPriority,
                onSelected = onPrioritySelected,
                modifier = Modifier.weight(1f)
            )
            DueDateSelector(
                selectedDueDate = selectedDueDate,
                onDueDateSelected = onDueDateSelected,
                onClearDueDate = onClearDueDate,
                modifier = Modifier.weight(1f)
            )
        }

        TagSelector(
            tags = tags,
            selectedTagId = selectedTagId,
            onSelectedTagIdChange = onSelectedTagIdChange,
            onCreateTag = onCreateTag,
            onRenameTag = onRenameTag,
            onDeleteTag = onDeleteTag
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f), enabled = title.isNotBlank()) {
                Text("Guardar")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Borrar tarea") },
            text = { Text("Estas seguro de que quieres borrar esta tarea?") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Borrar")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun applyFilters(
    tasks: List<Task>,
    selectedTag: String?
): List<Task> {
    var filtered = tasks
    val normalizedSelectedTag = selectedTag?.trim()?.takeIf { it.isNotEmpty() }

    val beforeTagCount = filtered.size
    if (normalizedSelectedTag != null) {
        filtered = filtered.filter { task ->
            task.tagId?.trim() == normalizedSelectedTag
        }
    }
    val afterTagCount = filtered.size

    Log.d(
        TAG_LOG,
        "applyFilters selectedTag=$normalizedSelectedTag beforeTag=$beforeTagCount afterTag=$afterTagCount"
    )

    return filtered
}

@Composable
private fun TagFilterSheet(
    availableTags: List<Tag>,
    selectedTag: String?,
    onSelectTag: (String) -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit
) {
    val tagColumns = remember(availableTags) {
        val items = listOf<Tag?>(null) + availableTags
        items.chunked(4)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Filtrar por tag", style = MaterialTheme.typography.titleMedium)

        if (availableTags.isEmpty()) {
            Text("No hay tags disponibles")
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(tagColumns, key = { column ->
                    column.joinToString("|") { it?.id ?: "all" }
                }) { column ->
                    Column(
                        modifier = Modifier.width(180.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        column.forEach { tag ->
                            val tagId = tag?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (tagId == null) {
                                            onClear()
                                        } else {
                                            onSelectTag(tagId)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = if (tagId == null) selectedTag == null else tagId == selectedTag,
                                    onClick = {
                                        if (tagId == null) {
                                            onClear()
                                        } else {
                                            onSelectTag(tagId)
                                        }
                                    }
                                )
                                Text(tag?.name ?: "Todos")
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text("Limpiar")
            }
            Button(onClick = onApply, modifier = Modifier.weight(1f)) {
                Text("Aplicar")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TagSelector(
    tags: List<Tag>,
    selectedTagId: String?,
    onSelectedTagIdChange: (String?) -> Unit,
    onCreateTag: (String, (Result<Tag>) -> Unit) -> Unit,
    onRenameTag: (Tag, String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteTag: (Tag, (Result<Unit>) -> Unit) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var showCreateTagSheet by remember { mutableStateOf(false) }
    var showManageTagsSheet by remember { mutableStateOf(false) }
    var renameTargetTag by remember { mutableStateOf<Tag?>(null) }
    var deleteTargetTag by remember { mutableStateOf<Tag?>(null) }
    var newTagName by remember { mutableStateOf("") }
    var renameTagName by remember { mutableStateOf("") }
    var tagActionError by remember { mutableStateOf<String?>(null) }
    var isSubmittingTagAction by remember { mutableStateOf(false) }
    val selectedLabel = when {
        selectedTagId == null -> "Sin tag"
        else -> tags.firstOrNull { it.id == selectedTagId }?.name ?: "Tag no disponible"
    }

    Column {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tag: $selectedLabel")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Sin tag") },
                onClick = {
                    onSelectedTagIdChange(null)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Nuevo tag") },
                onClick = {
                    expanded = false
                    newTagName = ""
                    tagActionError = null
                    showCreateTagSheet = true
                }
            )
            if (tags.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Gestionar tags") },
                    onClick = {
                        expanded = false
                        tagActionError = null
                        showManageTagsSheet = true
                    }
                )
            }
            if (tags.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No hay tags") },
                    onClick = { expanded = false }
                )
            } else {
                tags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag.name) },
                        onClick = {
                            onSelectedTagIdChange(tag.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    if (showCreateTagSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateTagSheet = false }) {
            TagNameSheet(
                title = "Nuevo tag",
                actionLabel = if (isSubmittingTagAction) "Creando..." else "Crear",
                value = newTagName,
                onValueChange = {
                    newTagName = it
                    tagActionError = null
                },
                errorMessage = tagActionError,
                isSubmitting = isSubmittingTagAction,
                onCancel = { showCreateTagSheet = false },
                onConfirm = {
                    val candidate = newTagName.trim()
                    if (candidate.isEmpty()) {
                        tagActionError = "El nombre del tag es obligatorio"
                    } else {
                        isSubmittingTagAction = true
                        onCreateTag(candidate) { result ->
                            isSubmittingTagAction = false
                            result.onSuccess { tag ->
                                onSelectedTagIdChange(tag.id)
                                showCreateTagSheet = false
                            }.onFailure { error ->
                                tagActionError = error.message ?: "No se pudo crear el tag"
                            }
                        }
                    }
                }
            )
        }
    }

    if (showManageTagsSheet) {
        ModalBottomSheet(onDismissRequest = { showManageTagsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Gestionar tags", style = MaterialTheme.typography.titleMedium)

                if (tags.isEmpty()) {
                    Text("No hay tags")
                } else {
                    tags.forEach { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tag.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            OutlinedButton(
                                onClick = {
                                    renameTargetTag = tag
                                    renameTagName = tag.name
                                    tagActionError = null
                                },
                                enabled = !isSubmittingTagAction
                            ) {
                                Text("Renombrar")
                            }
                            OutlinedButton(
                                onClick = {
                                    deleteTargetTag = tag
                                    tagActionError = null
                                },
                                enabled = !isSubmittingTagAction
                            ) {
                                Text("Borrar")
                            }
                        }
                    }
                }

                tagActionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = { showManageTagsSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmittingTagAction
                ) {
                    Text("Cerrar")
                }
            }
        }
    }

    renameTargetTag?.let { tag ->
        ModalBottomSheet(onDismissRequest = { if (!isSubmittingTagAction) renameTargetTag = null }) {
            TagNameSheet(
                title = "Renombrar tag",
                actionLabel = if (isSubmittingTagAction) "Guardando..." else "Guardar",
                value = renameTagName,
                onValueChange = {
                    renameTagName = it
                    tagActionError = null
                },
                errorMessage = tagActionError,
                isSubmitting = isSubmittingTagAction,
                onCancel = { renameTargetTag = null },
                onConfirm = {
                    val candidate = renameTagName.trim()
                    if (candidate.isEmpty()) {
                        tagActionError = "El nombre del tag es obligatorio"
                    } else {
                        isSubmittingTagAction = true
                        onRenameTag(tag, candidate) { result ->
                            isSubmittingTagAction = false
                            result.onSuccess {
                                renameTargetTag = null
                            }.onFailure { error ->
                                tagActionError = error.message ?: "No se pudo renombrar el tag"
                            }
                        }
                    }
                }
            )
        }
    }

    deleteTargetTag?.let { tag ->
        ModalBottomSheet(onDismissRequest = { if (!isSubmittingTagAction) deleteTargetTag = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Borrar tag", style = MaterialTheme.typography.titleMedium)
                Text("Las tareas que usen \"${tag.name}\" quedaran sin tag.")

                tagActionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { deleteTargetTag = null },
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmittingTagAction
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            isSubmittingTagAction = true
                            onDeleteTag(tag) { result ->
                                isSubmittingTagAction = false
                                result.onSuccess {
                                    if (selectedTagId == tag.id) {
                                        onSelectedTagIdChange(null)
                                    }
                                    deleteTargetTag = null
                                }.onFailure { error ->
                                    tagActionError = error.message ?: "No se pudo borrar el tag"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isSubmittingTagAction
                    ) {
                        Text(if (isSubmittingTagAction) "Borrando..." else "Borrar")
                    }
                }
            }
        }
    }
}

@Composable
private fun TagNameSheet(
    title: String,
    actionLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    errorMessage: String?,
    isSubmitting: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words
            ),
            singleLine = true,
            enabled = !isSubmitting,
            label = { Text("Nombre") }
        )

        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting
            ) {
                Text("Cancelar")
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    showArchiveAsUnarchive: Boolean,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onArchiveWithUndo: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onEditTask: (Task) -> Unit
) {
    if (tasks.isEmpty()) {
        Text("Sin tareas")
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 84.dp)) {
        items(tasks, key = { it.id }) { task ->
            if (showArchiveAsUnarchive) {
                SwipeTaskItem(
                    startToEndLabel = "Desarchivar",
                    endToStartLabel = null,
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = false,
                    onStartToEnd = {
                        onToggleArchived(task)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Tarea desarchivada",
                                actionLabel = "Deshacer",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onToggleArchived(task)
                            }
                        }
                    },
                    onEndToStart = null
                ) {
                    TaskRow(
                        task = task,
                        showArchiveAsUnarchive = true,
                        onToggleCompleted = { onToggleCompleted(task) },
                        onChangePriority = { onChangePriority(task, it) },
                        onToggleArchived = { onToggleArchived(task) },
                        onDeleteTask = { onDeleteTask(task) },
                        onEditTask = { onEditTask(task) }
                    )
                }
            } else {
                SwipeTaskItem(
                    startToEndLabel = "Hecho",
                    endToStartLabel = "Archivar",
                    enableDismissFromStartToEnd = true,
                    enableDismissFromEndToStart = true,
                    onStartToEnd = { onToggleCompleted(task) },
                    onEndToStart = { onArchiveWithUndo(task) }
                ) {
                    TaskRow(
                        task = task,
                        showArchiveAsUnarchive = false,
                        onToggleCompleted = { onToggleCompleted(task) },
                        onChangePriority = { onChangePriority(task, it) },
                        onToggleArchived = { onToggleArchived(task) },
                        onDeleteTask = { onDeleteTask(task) },
                        onEditTask = { onEditTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeTaskItem(
    startToEndLabel: String?,
    endToStartLabel: String?,
    enableDismissFromStartToEnd: Boolean,
    enableDismissFromEndToStart: Boolean,
    onStartToEnd: (() -> Unit)?,
    onEndToStart: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    var isVisible by remember { mutableStateOf(true) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!enableDismissFromStartToEnd || onStartToEnd == null || !isVisible) return@rememberSwipeToDismissBoxState false
                    pendingAction = onStartToEnd
                    isVisible = false
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!enableDismissFromEndToStart || onEndToStart == null || !isVisible) return@rememberSwipeToDismissBoxState false
                    pendingAction = onEndToStart
                    isVisible = false
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            delay(180)
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        exit = fadeOut() + shrinkVertically()
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = enableDismissFromStartToEnd,
            enableDismissFromEndToStart = enableDismissFromEndToStart,
            backgroundContent = {
                val direction = dismissState.dismissDirection
                val label = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> startToEndLabel
                    SwipeToDismissBoxValue.EndToStart -> endToStartLabel
                    SwipeToDismissBoxValue.Settled -> null
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentAlignment = when (direction) {
                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                        SwipeToDismissBoxValue.Settled -> Alignment.CenterStart
                    }
                ) {
                    if (label != null) {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RectangleShape)
            ) {
                content()
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    showArchiveAsUnarchive: Boolean,
    onToggleCompleted: () -> Unit,
    onChangePriority: (TaskPriority) -> Unit,
    onToggleArchived: () -> Unit,
    onDeleteTask: () -> Unit,
    onEditTask: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val priorityColor = task.priority.toIndicatorColor()
    val indicatorColor = if (task.completed) priorityColor.copy(alpha = 0.35f) else priorityColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onEditTask)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .background(indicatorColor, RoundedCornerShape(3.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = task.title,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
        )

        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onToggleCompleted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Completar",
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = { menuExpanded = true }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Mas acciones",
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(if (showArchiveAsUnarchive) "Desarchivar" else "Archivar") },
                onClick = {
                    menuExpanded = false
                    onToggleArchived()
                }
            )
            TaskPriority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text("Prioridad: ${priority.value}") },
                    onClick = {
                        menuExpanded = false
                        onChangePriority(priority)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Borrar") },
                onClick = {
                    menuExpanded = false
                    onDeleteTask()
                }
            )
        }
    }
}

@Composable
private fun TaskPriority.toIndicatorColor(): Color {
    return when (this) {
        TaskPriority.URGENTE -> MaterialTheme.colorScheme.error
        TaskPriority.ALTO -> MaterialTheme.colorScheme.tertiary
        TaskPriority.MEDIO -> MaterialTheme.colorScheme.primary
        TaskPriority.BAJO -> MaterialTheme.colorScheme.secondary
    }
}

@Composable
private fun PrioritySelector(
    selected: TaskPriority,
    onSelected: (TaskPriority) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Button(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Prioridad: ${selected.value}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TaskPriority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.value) },
                    onClick = {
                        onSelected(priority)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DueDateSelector(
    selectedDueDate: Timestamp?,
    onDueDateSelected: (Timestamp?) -> Unit,
    onClearDueDate: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                val today = LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        val localDate = LocalDate.of(year, month + 1, dayOfMonth)
                        val instant = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
                        onDueDateSelected(Timestamp(instant.epochSecond, 0))
                    },
                    today.year,
                    today.monthValue - 1,
                    today.dayOfMonth
                ).show()
            },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(formatDueDate(selectedDueDate))
        }

        if (selectedDueDate != null) {
            Button(onClick = onClearDueDate, enabled = enabled) {
                Text("Quitar")
            }
        }
    }
}

private fun formatDueDate(timestamp: Timestamp?): String {
    if (timestamp == null) return "Sin fecha"

    val localDate = Instant.ofEpochSecond(timestamp.seconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    return localDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
}

