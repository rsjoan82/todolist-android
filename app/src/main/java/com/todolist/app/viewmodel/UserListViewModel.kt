package com.todolist.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import com.todolist.app.data.model.UserList
import com.todolist.app.data.model.UserListItem
import com.todolist.app.data.model.UserListItemPatch
import com.todolist.app.data.repository.UserListRepository
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

data class UserListSummary(
    val list: UserList,
    val pendingCount: Int,
    val totalCount: Int
) {
    val shortSummary: String
        get() = "$pendingCount pendientes \u00b7 $totalCount total"
}

data class UserListUiState(
    val user: FirebaseUser? = null,
    val listSummaries: List<UserListSummary> = emptyList(),
    val selectedListId: String? = null,
    val selectedListItems: List<UserListItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedListSummary: UserListSummary?
        get() = listSummaries.firstOrNull { it.list.id == selectedListId }
}

class UserListViewModel(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val repository: UserListRepository = UserListRepository()
) : ViewModel() {
    companion object {
        private const val STARTUP_LOG_TAG = "TodoListStartup"
    }

    private val _uiState = MutableStateFlow(UserListUiState(user = auth.currentUser))
    val uiState: StateFlow<UserListUiState> = _uiState.asStateFlow()

    private var listsJob: Job? = null
    private var itemsJob: Job? = null
    private var latestLists: List<UserList> = emptyList()
    private var latestItems: List<UserListItem> = emptyList()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        Log.d(STARTUP_LOG_TAG, "UserListViewModel authStateListener user=${user?.uid ?: "null"}")
        _uiState.update { it.copy(user = user, errorMessage = null) }

        if (user == null) {
            clearData()
        } else {
            observeData(user.uid)
        }
    }

    init {
        Log.d(STARTUP_LOG_TAG, "UserListViewModel init currentUser=${auth.currentUser?.uid ?: "null"}")
        auth.addAuthStateListener(authStateListener)
        auth.currentUser?.uid?.let {
            Log.d(STARTUP_LOG_TAG, "UserListViewModel init observing uid=$it")
            observeData(it)
        }
    }

    override fun onCleared() {
        listsJob?.cancel()
        itemsJob?.cancel()
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun selectList(listId: String?) {
        Log.d(STARTUP_LOG_TAG, "UserListViewModel selectList id=${listId ?: "null"}")
        _uiState.update { state ->
            state.copy(
                selectedListId = listId,
                selectedListItems = latestItems
                    .filter { it.listId == listId }
                    .sortedForList()
            )
        }
    }

    fun createList(name: String) = launchWrite {
        val uid = requireUid()
        repository.createList(uid, name)
    }

    fun renameList(list: UserList, name: String) = launchWrite {
        val uid = requireUid()
        repository.renameList(uid, list.id, name)
    }

    fun deleteList(list: UserList) = launchWrite {
        val uid = requireUid()
        repository.deleteList(uid, list.id)
        if (_uiState.value.selectedListId == list.id) {
            _uiState.update { it.copy(selectedListId = null, selectedListItems = emptyList()) }
        }
    }

    fun createListItem(listId: String, text: String) = launchWrite {
        val uid = requireUid()
        repository.createListItem(uid, listId, text)
    }

    fun editListItem(item: UserListItem, text: String) = launchWrite {
        val uid = requireUid()
        repository.updateListItem(uid, item.listId, item.id, UserListItemPatch(text = text))
    }

    fun toggleListItemChecked(item: UserListItem) = launchWrite {
        val uid = requireUid()
        repository.toggleListItemChecked(uid, item)
    }

    fun deleteListItem(item: UserListItem) = launchWrite {
        val uid = requireUid()
        repository.deleteListItem(uid, item.listId, item.id)
    }

    private fun observeData(uid: String) {
        Log.d(STARTUP_LOG_TAG, "UserListViewModel observeData uid=$uid")
        listsJob?.cancel()
        itemsJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        listsJob = viewModelScope.launch {
            repository.getLists(uid)
                .catch { error ->
                    Log.e(STARTUP_LOG_TAG, "UserListViewModel lists flow error uid=$uid", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar las listas"
                        )
                    }
                }
                .collect { lists ->
                    Log.d(STARTUP_LOG_TAG, "UserListViewModel lists collected count=${lists.size}")
                    latestLists = lists
                    syncUiState()
                }
        }
    }

    private fun syncUiState() {
        Log.d(
            STARTUP_LOG_TAG,
            "UserListViewModel syncUiState lists=${latestLists.size} items=${latestItems.size}"
        )
        val selectedListId = updateListSummaries()

        _uiState.update { state ->
            state.copy(
                selectedListId = selectedListId,
                selectedListItems = latestItems
                    .filter { it.listId == selectedListId }
                    .sortedForList(),
                isLoading = false
            )
        }

        observeItemsForCurrentLists()
    }

    private fun updateListSummaries(): String? {
        val summaries = latestLists
            .map { list ->
                val listItems = latestItems.filter { it.listId == list.id }
                UserListSummary(
                    list = list,
                    pendingCount = listItems.count { !it.checked },
                    totalCount = listItems.size
                )
            }
            .sortedWith(
                compareByDescending<UserListSummary> { it.pendingCount }
                    .thenBy { it.list.name.trim().lowercase() }
                    .thenBy { it.list.id }
            )

        val selectedListId = _uiState.value.selectedListId
            ?.takeIf { listId -> summaries.any { it.list.id == listId } }

        _uiState.update { state ->
            state.copy(
                listSummaries = summaries,
                selectedListId = selectedListId
            )
        }

        return selectedListId
    }

    private fun observeItemsForCurrentLists() {
        val uid = _uiState.value.user?.uid ?: return
        val listIds = latestLists.map { it.id }.filter { it.isNotBlank() }

        itemsJob?.cancel()
        if (listIds.isEmpty()) {
            Log.d(STARTUP_LOG_TAG, "UserListViewModel no lists; clearing items")
            latestItems = emptyList()
            _uiState.update { state ->
                state.copy(selectedListItems = emptyList(), isLoading = false)
            }
            return
        }

        itemsJob = viewModelScope.launch {
            repository.getListItems(uid, listIds)
                .catch { error ->
                    Log.e(STARTUP_LOG_TAG, "UserListViewModel items flow error uid=$uid listIds=$listIds", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "No se pudieron cargar los items de la lista"
                        )
                    }
                }
                .collect { items ->
                    Log.d(STARTUP_LOG_TAG, "UserListViewModel items collected count=${items.size}")
                    latestItems = items
                    val selectedListId = updateListSummaries()
                    _uiState.update { state ->
                        state.copy(
                            selectedListId = selectedListId,
                            selectedListItems = latestItems
                                .filter { it.listId == selectedListId }
                                .sortedForList(),
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun clearData() {
        Log.d(STARTUP_LOG_TAG, "UserListViewModel clearData")
        listsJob?.cancel()
        itemsJob?.cancel()
        latestLists = emptyList()
        latestItems = emptyList()
        _uiState.update {
            it.copy(
                listSummaries = emptyList(),
                selectedListId = null,
                selectedListItems = emptyList(),
                isLoading = false
            )
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
                Log.e(STARTUP_LOG_TAG, "UserListViewModel write error", error)
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

        return error.message ?: "No se pudo guardar la lista"
    }

    private fun List<UserListItem>.sortedForList(): List<UserListItem> {
        return sortedWith(
            compareBy<UserListItem> { it.checked }
                .thenByDescending { it.updatedAt?.seconds ?: 0L }
                .thenByDescending { it.createdAt?.seconds ?: 0L }
        )
    }
}
