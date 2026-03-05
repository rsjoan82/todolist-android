package com.todolist.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskPatch
import com.todolist.app.data.model.TaskPriority
import com.todolist.app.data.model.TaskStatus
import com.todolist.app.data.repository.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskUiState(
    val user: FirebaseUser? = null,
    val openTasks: List<Task> = emptyList(),
    val doneTasks: List<Task> = emptyList(),
    val archivedTasks: List<Task> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class TaskViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: TaskRepository = TaskRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskUiState(user = auth.currentUser))
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private var openTasksJob: Job? = null
    private var doneTasksJob: Job? = null
    private var archivedTasksJob: Job? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _uiState.update { state ->
            state.copy(user = user, errorMessage = null)
        }

        if (user == null) {
            clearTasks()
        } else {
            observeTasks(user.uid)
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
        auth.currentUser?.uid?.let { observeTasks(it) }
    }

    override fun onCleared() {
        openTasksJob?.cancel()
        doneTasksJob?.cancel()
        archivedTasksJob?.cancel()
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun addTask(title: String, priority: TaskPriority, dueDate: Timestamp?, tagsText: String) {
        val uid = _uiState.value.user?.uid ?: return
        val tags = tagsText
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        viewModelScope.launch {
            runCatching {
                repository.addTask(uid, title, priority, dueDate, tags)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "No se pudo anadir la tarea")
                }
            }
        }
    }

    fun toggleCompleted(task: Task) {
        updateTask(task.id, TaskPatch(toggleCompleted = true))
    }

    fun toggleArchived(task: Task) {
        updateTask(task.id, TaskPatch(toggleArchived = true))
    }

    fun changePriority(task: Task, priority: TaskPriority) {
        updateTask(task.id, TaskPatch(priority = priority))
    }

    fun changeStatus(task: Task, status: TaskStatus) {
        updateTask(task.id, TaskPatch(status = status))
    }

    fun clearDueDate(task: Task) {
        updateTask(task.id, TaskPatch(dueDate = null, setDueDate = true))
    }

    fun setDueDate(task: Task, dueDate: Timestamp?) {
        updateTask(task.id, TaskPatch(dueDate = dueDate, setDueDate = true))
    }

    fun saveTaskEdits(
        task: Task,
        title: String,
        priority: TaskPriority,
        dueDate: Timestamp?,
        tagsText: String
    ) {
        val cleanTitle = title.trim()
        val cleanTags = tagsText
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val titleChanged = cleanTitle.isNotBlank() && cleanTitle != task.title
        val priorityChanged = priority != task.priority
        val dueDateChanged = !sameTimestamp(dueDate, task.dueDate)
        val tagsChanged = cleanTags != task.tags

        if (!titleChanged && !priorityChanged && !dueDateChanged && !tagsChanged) return

        updateTask(
            task.id,
            TaskPatch(
                title = if (titleChanged) cleanTitle else null,
                priority = if (priorityChanged) priority else null,
                dueDate = dueDate,
                setDueDate = dueDateChanged,
                tags = if (tagsChanged) cleanTags else null
            )
        )
    }

    fun deleteTask(task: Task) {
        val uid = _uiState.value.user?.uid ?: return
        if (task.id.isBlank()) return

        viewModelScope.launch {
            runCatching {
                repository.deleteTask(uid, task.id)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "No se pudo borrar la tarea")
                }
            }
        }
    }

    private fun updateTask(taskId: String, patch: TaskPatch) {
        val uid = _uiState.value.user?.uid ?: return
        if (taskId.isBlank()) return

        viewModelScope.launch {
            runCatching {
                repository.updateTask(uid, taskId, patch)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "No se pudo actualizar la tarea")
                }
            }
        }
    }

    private fun sameTimestamp(a: Timestamp?, b: Timestamp?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.seconds == b.seconds && a.nanoseconds == b.nanoseconds
    }

    private fun clearTasks() {
        openTasksJob?.cancel()
        doneTasksJob?.cancel()
        archivedTasksJob?.cancel()
        _uiState.update {
            it.copy(
                openTasks = emptyList(),
                doneTasks = emptyList(),
                archivedTasks = emptyList(),
                isLoading = false
            )
        }
    }

    private fun observeTasks(uid: String) {
        openTasksJob?.cancel()
        doneTasksJob?.cancel()
        archivedTasksJob?.cancel()

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        openTasksJob = viewModelScope.launch {
            repository.getOpenTasks(uid)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar tareas abiertas"
                        )
                    }
                }
                .collect { tasks ->
                    _uiState.update { it.copy(openTasks = tasks, isLoading = false) }
                }
        }

        doneTasksJob = viewModelScope.launch {
            repository.getDoneTasks(uid)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar tareas hechas"
                        )
                    }
                }
                .collect { tasks ->
                    _uiState.update { it.copy(doneTasks = tasks, isLoading = false) }
                }
        }

        archivedTasksJob = viewModelScope.launch {
            repository.getArchivedTasks(uid)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar tareas archivadas"
                        )
                    }
                }
                .collect { tasks ->
                    _uiState.update { it.copy(archivedTasks = tasks, isLoading = false) }
                }
        }
    }
}
