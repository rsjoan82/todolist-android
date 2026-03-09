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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
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
private enum class TagFilterMode { OR, AND }
private const val TAG_LOG = "TodoListTags"
private const val TAG_UNDO = "TodoListUndo"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TaskScreen(
    openTasks: List<Task>,
    doneTasks: List<Task>,
    archivedTasks: List<Task>,
    isLoading: Boolean,
    errorMessage: String?,
    onAddTask: (
        title: String,
        priority: TaskPriority,
        dueDate: Timestamp?,
        tagsText: String,
        onResult: (Result<Unit>) -> Unit
    ) -> Unit,
    isCreating: Boolean,
    createError: String?,
    onClearCreateError: () -> Unit,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onSaveTask: (task: Task, title: String, priority: TaskPriority, dueDate: Timestamp?, tagsText: String) -> Unit,
    hasSession: Boolean,
    openFilterRequest: Int = 0,
    openCreateRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(TaskTab.OPEN) }

    var showCreateSheet by remember { mutableStateOf(false) }
    var createTitle by remember { mutableStateOf("") }
    var createTagsInput by remember { mutableStateOf("") }
    var createPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
    var createDueDate by remember { mutableStateOf<Timestamp?>(null) }

    var editingTask by remember { mutableStateOf<Task?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editTagsInput by remember { mutableStateOf("") }
    var editPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
    var editDueDate by remember { mutableStateOf<Timestamp?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showTagFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var selectedTags by remember { mutableStateOf(TaskFilterPreferences.getSelectedTags(context)) }
    var draftSelectedTags by remember { mutableStateOf(selectedTags) }
    val tagFilterMode = TagFilterMode.OR
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

    val availableTags = remember(openTasks, doneTasks, archivedTasks) {
        (openTasks + doneTasks + archivedTasks)
            .flatMap { it.tags }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .sortedBy { it.lowercase() }
    }

    val normalizedQuery = searchQuery.trim()

    val filteredOpenTasks = remember(openTasks, normalizedQuery, selectedTags, tagFilterMode) {
        applyFilters(openTasks, normalizedQuery, selectedTags, tagFilterMode)
    }
    val filteredDoneTasks = remember(doneTasks, normalizedQuery, selectedTags, tagFilterMode) {
        applyFilters(doneTasks, normalizedQuery, selectedTags, tagFilterMode)
    }
    val filteredArchivedTasks = remember(archivedTasks, normalizedQuery, selectedTags, tagFilterMode) {
        applyFilters(archivedTasks, normalizedQuery, selectedTags, tagFilterMode)
    }

    LaunchedEffect(openFilterRequest) {
        if (openFilterRequest > 0) {
            draftSelectedTags = selectedTags
            showTagFilterSheet = true
        }
    }

    LaunchedEffect(openCreateRequest) {
        if (openCreateRequest > 0) {
            createTitle = ""
            createTagsInput = ""
            createPriority = TaskPriority.MEDIO
            createDueDate = null
            onClearCreateError()
            showCreateSheet = true
        }
    }

    val openCreateSheet = {
        createTitle = ""
        createTagsInput = ""
        createPriority = TaskPriority.MEDIO
        createDueDate = null
        onClearCreateError()
        showCreateSheet = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "Buscar")
                },
                label = { Text("Buscar tareas") }
            )

            Spacer(modifier = Modifier.height(8.dp))

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

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
            }

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
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
                            editTagsInput = task.tags.joinToString(",")
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
                            editTagsInput = task.tags.joinToString(",")
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
                            editTagsInput = task.tags.joinToString(",")
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
                title = createTitle,
                onTitleChange = { createTitle = it },
                tagsInput = createTagsInput,
                onTagsInputChange = { createTagsInput = it },
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
                            createTagsInput
                        ) { result ->
                            result.onSuccess {
                                showCreateSheet = false
                                createTitle = ""
                                createTagsInput = ""
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
                availableTags = availableTags,
                selectedTags = draftSelectedTags,
                onToggleTag = { tag ->
                    draftSelectedTags = if (tag in draftSelectedTags) {
                        draftSelectedTags - tag
                    } else {
                        draftSelectedTags + tag
                    }
                },
                onClear = { draftSelectedTags = emptySet() },
                onApply = {
                    selectedTags = draftSelectedTags
                    TaskFilterPreferences.saveSelectedTags(context, selectedTags)
                    showTagFilterSheet = false
                }
            )
        }
    }

    editingTask?.let { task ->
        ModalBottomSheet(onDismissRequest = { editingTask = null }) {
            EditTaskSheetContent(
                title = editTitle,
                onTitleChange = { editTitle = it },
                tagsInput = editTagsInput,
                onTagsInputChange = { editTagsInput = it },
                selectedPriority = editPriority,
                onPrioritySelected = { editPriority = it },
                selectedDueDate = editDueDate,
                onDueDateSelected = { editDueDate = it },
                onClearDueDate = { editDueDate = null },
                onSave = {
                    onSaveTask(task, editTitle, editPriority, editDueDate, editTagsInput)
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
private fun NewTaskSheetContent(
    title: String,
    onTitleChange: (String) -> Unit,
    tagsInput: String,
    onTagsInputChange: (String) -> Unit,
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

        OutlinedTextField(
            value = tagsInput,
            onValueChange = onTagsInputChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isCreating,
            label = { Text("Tags (coma)") }
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
    title: String,
    onTitleChange: (String) -> Unit,
    tagsInput: String,
    onTagsInputChange: (String) -> Unit,
    selectedPriority: TaskPriority,
    onPrioritySelected: (TaskPriority) -> Unit,
    selectedDueDate: Timestamp?,
    onDueDateSelected: (Timestamp?) -> Unit,
    onClearDueDate: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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

        OutlinedTextField(
            value = tagsInput,
            onValueChange = onTagsInputChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Tags (coma)") }
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

        Button(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text("Borrar")
        }
    }
}

private fun applyFilters(
    tasks: List<Task>,
    searchQuery: String,
    selectedTags: Set<String>,
    mode: TagFilterMode
): List<Task> {
    var filtered = tasks
    val normalizedSelectedTags = selectedTags
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    if (searchQuery.isNotEmpty()) {
        filtered = filtered.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val beforeTagsCount = filtered.size
    if (normalizedSelectedTags.isNotEmpty()) {
        filtered = when (mode) {
            TagFilterMode.OR -> filtered.filter { task ->
                task.tags.any { it.trim().lowercase() in normalizedSelectedTags }
            }
            TagFilterMode.AND -> filtered.filter { task ->
                normalizedSelectedTags.all { selected ->
                    task.tags.any { it.trim().lowercase() == selected }
                }
            }
        }
    }
    val afterTagsCount = filtered.size

    Log.d(
        TAG_LOG,
        "applyFilters selectedTags=$normalizedSelectedTags beforeTags=$beforeTagsCount afterTags=$afterTagsCount mode=$mode search='$searchQuery'"
    )

    return filtered
}

@Composable
private fun TagFilterSheet(
    availableTags: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Filtrar por tags", style = MaterialTheme.typography.titleMedium)

        if (availableTags.isEmpty()) {
            Text("No hay tags disponibles")
        } else {
            LazyColumn(modifier = Modifier.height(220.dp)) {
                items(availableTags, key = { it }) { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleTag(tag) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tag in selectedTags,
                            onCheckedChange = { onToggleTag(tag) }
                        )
                        Text(tag)
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

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
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
            .clickable(onClick = onEditTask)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(22.dp)
                .background(indicatorColor, RoundedCornerShape(3.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = task.title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
        )

        IconButton(onClick = onToggleCompleted) {
            Icon(imageVector = Icons.Filled.Check, contentDescription = "Completar")
        }

        IconButton(onClick = { menuExpanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Mas acciones")
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
