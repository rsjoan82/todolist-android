package com.todolist.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import com.todolist.app.data.model.Project
import com.todolist.app.data.model.ProjectItem
import com.todolist.app.data.model.ProjectItemPatch
import com.todolist.app.data.model.ProjectItemType
import com.todolist.app.data.model.ProjectStatus
import com.todolist.app.data.repository.ProjectRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.UnknownHostException

data class ProjectSummary(
    val project: Project,
    val pendingCount: Int,
    val totalCount: Int,
    val status: ProjectStatus
) {
    val shortSummary: String
        get() = "$pendingCount pendientes · $totalCount total"
}

data class ProjectUiState(
    val user: FirebaseUser? = null,
    val projectSummaries: List<ProjectSummary> = emptyList(),
    val selectedProjectId: String? = null,
    val selectedProjectItems: List<ProjectItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedProjectSummary: ProjectSummary?
        get() = projectSummaries.firstOrNull { it.project.id == selectedProjectId }
}

class ProjectViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: ProjectRepository = ProjectRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProjectUiState(user = auth.currentUser))
    val uiState: StateFlow<ProjectUiState> = _uiState.asStateFlow()

    private var projectsJob: Job? = null
    private var itemsJob: Job? = null
    private var latestProjects: List<Project> = emptyList()
    private var latestItems: List<ProjectItem> = emptyList()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _uiState.update { it.copy(user = user, errorMessage = null) }

        if (user == null) {
            clearData()
        } else {
            observeData(user.uid)
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
        auth.currentUser?.uid?.let(::observeData)
    }

    override fun onCleared() {
        projectsJob?.cancel()
        itemsJob?.cancel()
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun selectProject(projectId: String?) {
        _uiState.update { state ->
            state.copy(
                selectedProjectId = projectId,
                selectedProjectItems = latestItems
                    .filter { it.projectId == projectId }
                    .sortedForProject()
            )
        }
    }

    fun createProject(name: String) = launchWrite {
        val uid = requireUid()
        repository.createProject(uid, name)
    }

    fun renameProject(project: Project, name: String) = launchWrite {
        val uid = requireUid()
        repository.renameProject(uid, project.id, name)
    }

    fun deleteProject(project: Project) = launchWrite {
        val uid = requireUid()
        repository.deleteProject(uid, project.id)
        if (_uiState.value.selectedProjectId == project.id) {
            _uiState.update { it.copy(selectedProjectId = null, selectedProjectItems = emptyList()) }
        }
    }

    fun createProjectItem(
        projectId: String,
        title: String,
        description: String,
        type: ProjectItemType
    ) = launchWrite {
        val uid = requireUid()
        repository.createProjectItem(uid, projectId, title, description, type)
    }

    fun editProjectItem(
        item: ProjectItem,
        title: String,
        description: String,
        type: ProjectItemType
    ) = launchWrite {
        val uid = requireUid()
        repository.updateProjectItem(
            uid,
            item.projectId,
            item.id,
            ProjectItemPatch(title = title, description = description, type = type)
        )
    }

    fun toggleProjectItemDone(item: ProjectItem) = launchWrite {
        val uid = requireUid()
        repository.toggleProjectItemDone(uid, item)
    }

    fun deleteProjectItem(item: ProjectItem) = launchWrite {
        val uid = requireUid()
        repository.deleteProjectItem(uid, item.projectId, item.id)
    }

    private fun observeData(uid: String) {
        projectsJob?.cancel()
        itemsJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        projectsJob = viewModelScope.launch {
            repository.getProjects(uid)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar los proyectos"
                        )
                    }
                }
                .collect { projects ->
                    latestProjects = projects
                    syncUiState()
                }
        }

        itemsJob = viewModelScope.launch {
            runCatching {
                repository.migrateLegacyProjectItems(uid)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "No se pudieron migrar los items legacy")
                }
            }
        }
    }

    private fun syncUiState() {
        val summaries = latestProjects.map { project ->
            val projectItems = latestItems.filter { it.projectId == project.id }
            val pendingCount = projectItems.count { !it.done }
            ProjectSummary(
                project = project,
                pendingCount = pendingCount,
                totalCount = projectItems.size,
                status = if (pendingCount > 0) ProjectStatus.PENDING else ProjectStatus.COMPLETED
            )
        }

        _uiState.update { state ->
            val selectedProjectId = state.selectedProjectId
                ?.takeIf { projectId -> summaries.any { it.project.id == projectId } }

            state.copy(
                projectSummaries = summaries,
                selectedProjectId = selectedProjectId,
                selectedProjectItems = latestItems
                    .filter { it.projectId == selectedProjectId }
                    .sortedForProject(),
                isLoading = false
            )
        }

        observeItemsForCurrentProjects()
    }

    private fun clearData() {
        projectsJob?.cancel()
        itemsJob?.cancel()
        latestProjects = emptyList()
        latestItems = emptyList()
        _uiState.update {
            it.copy(
                projectSummaries = emptyList(),
                selectedProjectId = null,
                selectedProjectItems = emptyList(),
                isLoading = false
            )
        }
    }

    private fun observeItemsForCurrentProjects() {
        val uid = _uiState.value.user?.uid ?: return
        val projectIds = latestProjects.map { it.id }.filter { it.isNotBlank() }

        itemsJob?.cancel()
        if (projectIds.isEmpty()) {
            latestItems = emptyList()
            _uiState.update { state ->
                state.copy(selectedProjectItems = emptyList(), isLoading = false)
            }
            return
        }

        itemsJob = viewModelScope.launch {
            repository.getProjectItems(uid, projectIds)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar los items del proyecto"
                        )
                    }
                }
                .collect { items ->
                    latestItems = items
                    _uiState.update { state ->
                        state.copy(
                            selectedProjectItems = latestItems
                                .filter { it.projectId == state.selectedProjectId }
                                .sortedForProject(),
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                withTimeout(10_000L) {
                    block()
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(errorMessage = mapErrorMessage(error)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun requireUid(): String {
        return _uiState.value.user?.uid ?: throw IllegalStateException("No hay sesion")
    }

    private fun mapErrorMessage(error: Throwable): String {
        if (error is TimeoutCancellationException) {
            return "Sin conexion: no se pudo guardar"
        }

        if (error is FirebaseFirestoreException &&
            error.code == FirebaseFirestoreException.Code.UNAVAILABLE
        ) {
            return "Sin conexion: no se pudo guardar"
        }

        val hasUnknownHost = generateSequence(error) { it.cause }
            .any { it is UnknownHostException }
        if (hasUnknownHost) {
            return "Sin conexion: no se pudo guardar"
        }

        return error.message ?: "No se pudo guardar el proyecto"
    }

    private fun List<ProjectItem>.sortedForProject(): List<ProjectItem> {
        return sortedWith(
            compareBy<ProjectItem> { it.done }
                .thenByDescending { it.updatedAt?.seconds ?: 0L }
                .thenByDescending { it.createdAt?.seconds ?: 0L }
        )
    }
}
