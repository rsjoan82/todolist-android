package com.todolist.app.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TaskTab { OPEN, ARCHIVED }

@Composable
fun TaskScreen(
    openTasks: List<Task>,
    archivedTasks: List<Task>,
    isLoading: Boolean,
    errorMessage: String?,
    onAddTask: (title: String, priority: TaskPriority, dueDate: Timestamp?, tagsText: String) -> Unit,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(TaskTab.OPEN) }
    var title by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var selectedPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
    var selectedDueDate by remember { mutableStateOf<Timestamp?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        CreateTaskForm(
            title = title,
            onTitleChange = { title = it },
            tagsInput = tagsInput,
            onTagsInputChange = { tagsInput = it },
            selectedPriority = selectedPriority,
            onPrioritySelected = { selectedPriority = it },
            selectedDueDate = selectedDueDate,
            onDueDateSelected = { selectedDueDate = it },
            onClearDueDate = { selectedDueDate = null },
            onAddClick = {
                onAddTask(title, selectedPriority, selectedDueDate, tagsInput)
                title = ""
                tagsInput = ""
                selectedPriority = TaskPriority.MEDIO
                selectedDueDate = null
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = if (selectedTab == TaskTab.OPEN) 0 else 1) {
            Tab(
                selected = selectedTab == TaskTab.OPEN,
                onClick = { selectedTab = TaskTab.OPEN },
                text = { Text("Abiertas") }
            )
            Tab(
                selected = selectedTab == TaskTab.ARCHIVED,
                onClick = { selectedTab = TaskTab.ARCHIVED },
                text = { Text("Archivadas") }
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
            TaskList(
                tasks = openTasks,
                showArchiveAsUnarchive = false,
                onToggleCompleted = onToggleCompleted,
                onChangePriority = onChangePriority,
                onToggleArchived = onToggleArchived,
                onDeleteTask = onDeleteTask
            )
        } else {
            TaskList(
                tasks = archivedTasks,
                showArchiveAsUnarchive = true,
                onToggleCompleted = onToggleCompleted,
                onChangePriority = onChangePriority,
                onToggleArchived = onToggleArchived,
                onDeleteTask = onDeleteTask
            )
        }
    }
}

@Composable
private fun CreateTaskForm(
    title: String,
    onTitleChange: (String) -> Unit,
    tagsInput: String,
    onTagsInputChange: (String) -> Unit,
    selectedPriority: TaskPriority,
    onPrioritySelected: (TaskPriority) -> Unit,
    selectedDueDate: Timestamp?,
    onDueDateSelected: (Timestamp?) -> Unit,
    onClearDueDate: () -> Unit,
    onAddClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Button(
            onClick = onAddClick,
            enabled = title.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Anadir")
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
    onDeleteTask: (Task) -> Unit
) {
    if (tasks.isEmpty()) {
        Text("Sin tareas")
        return
    }

    LazyColumn {
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                showArchiveAsUnarchive = showArchiveAsUnarchive,
                onToggleCompleted = { onToggleCompleted(task) },
                onChangePriority = { onChangePriority(task, it) },
                onToggleArchived = { onToggleArchived(task) },
                onDeleteTask = { onDeleteTask(task) }
            )
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
    onDeleteTask: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = task.title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (task.completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
private fun PrioritySelector(
    selected: TaskPriority,
    onSelected: (TaskPriority) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
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
            modifier = Modifier.weight(1f)
        ) {
            Text(formatDueDate(selectedDueDate))
        }

        if (selectedDueDate != null) {
            Button(onClick = onClearDueDate) {
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
