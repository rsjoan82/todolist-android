package com.todolist.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.google.firebase.Timestamp
import com.todolist.app.data.model.TaskPriority
import com.todolist.app.ui.screens.NewTaskSheetContent
import com.todolist.app.ui.theme.TodoListTheme
import com.todolist.app.viewmodel.TaskViewModel
import com.todolist.app.widget.TodoListWidgetProvider

class WidgetCreateTaskActivity : ComponentActivity() {
    private val taskViewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TodoListTheme {
                val taskUiState by taskViewModel.uiState.collectAsState()
                var title by remember { mutableStateOf("") }
                var selectedTagId by remember { mutableStateOf<String?>(null) }
                var selectedPriority by remember { mutableStateOf(TaskPriority.MEDIO) }
                var selectedDueDate by remember { mutableStateOf<Timestamp?>(null) }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    NewTaskSheetContent(
                        tags = taskUiState.tags,
                        title = title,
                        onTitleChange = { title = it },
                        selectedTagId = selectedTagId,
                        onSelectedTagIdChange = { selectedTagId = it },
                        onCreateTag = taskViewModel::createTag,
                        onRenameTag = taskViewModel::renameTag,
                        onDeleteTag = taskViewModel::deleteTag,
                        selectedPriority = selectedPriority,
                        onPrioritySelected = { selectedPriority = it },
                        selectedDueDate = selectedDueDate,
                        onDueDateSelected = { selectedDueDate = it },
                        onClearDueDate = { selectedDueDate = null },
                        onCreate = {
                            taskViewModel.addTask(
                                title = title,
                                priority = selectedPriority,
                                dueDate = selectedDueDate,
                                tagId = selectedTagId
                            ) { result ->
                                result.onSuccess {
                                    TodoListWidgetProvider.requestRefresh(this@WidgetCreateTaskActivity)
                                    finish()
                                }
                            }
                        },
                        onCancel = { finish() },
                        hasSession = taskUiState.user != null,
                        createError = taskUiState.createError,
                        isCreating = taskUiState.isCreating
                    )
                }
            }
        }
    }
}
