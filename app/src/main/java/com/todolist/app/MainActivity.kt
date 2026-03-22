package com.todolist.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.todolist.app.data.model.Task
import com.todolist.app.data.model.TaskPriority
import com.todolist.app.ui.screens.TaskScreen
import com.todolist.app.ui.theme.TodoListTheme
import com.todolist.app.viewmodel.TaskUiState
import com.todolist.app.viewmodel.TaskViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthUiState(
    val user: FirebaseUser? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val _uiState = MutableStateFlow(AuthUiState(user = auth.currentUser))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _uiState.update {
            it.copy(
                user = firebaseAuth.currentUser,
                isLoading = false,
                errorMessage = if (firebaseAuth.currentUser != null) null else it.errorMessage
            )
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun onSignInStarted() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
    }

    fun onSignInFailed(message: String) {
        _uiState.update { it.copy(isLoading = false, errorMessage = message) }
    }

    fun onSignedOut() {
        auth.signOut()
        _uiState.update { it.copy(isLoading = false, errorMessage = null) }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_CREATE_TASK = "open_create_task"
        const val EXTRA_OPEN_TASK_ID = "open_task_id"
    }

    private val authViewModel: AuthViewModel by viewModels()
    private val taskViewModel: TaskViewModel by viewModels()
    private val openCreateRequest = mutableStateOf(0)
    private val openTaskRequest = mutableStateOf(0)
    private val openTaskId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        enableEdgeToEdge()
        setContent {
            TodoListTheme {
                AuthGate(
                    viewModel = authViewModel,
                    taskViewModel = taskViewModel,
                    openCreateRequest = openCreateRequest.value,
                    openTaskRequest = openTaskRequest.value,
                    openTaskId = openTaskId.value,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CREATE_TASK, false) == true) {
            openCreateRequest.value += 1
        }

        intent?.getStringExtra(EXTRA_OPEN_TASK_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { taskId ->
                openTaskId.value = taskId
                openTaskRequest.value += 1
            }
    }
}

@Composable
private fun AuthGate(
    viewModel: AuthViewModel,
    taskViewModel: TaskViewModel,
    openCreateRequest: Int,
    openTaskRequest: Int,
    openTaskId: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val taskUiState by taskViewModel.uiState.collectAsState()

    if (uiState.user == null) {
        LoginScreen(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            modifier = modifier,
            onGoogleSignInClick = {
                viewModel.onSignInStarted()
                scope.launch {
                    val result = runCatching {
                        signInWithGoogle(
                            auth = auth,
                            credentialManager = credentialManager,
                            serverClientId = context.getString(R.string.default_web_client_id),
                            context = context
                        )
                    }
                    result.exceptionOrNull()?.let { error ->
                        viewModel.onSignInFailed(mapSignInError(error))
                    }
                }
            }
        )
    } else {
        HomeScreen(
            taskUiState = taskUiState,
            modifier = modifier,
            onAddTask = taskViewModel::addTask,
            onCreateTag = taskViewModel::createTag,
            onRenameTag = taskViewModel::renameTag,
            onDeleteTag = taskViewModel::deleteTag,
            onClearCreateError = taskViewModel::clearCreateError,
            onToggleCompleted = taskViewModel::toggleCompleted,
            onChangePriority = taskViewModel::changePriority,
            onToggleArchived = taskViewModel::toggleArchived,
            onDeleteTask = taskViewModel::deleteTask,
            onSaveTask = taskViewModel::saveTaskEdits,
            openCreateRequest = openCreateRequest,
            openTaskRequest = openTaskRequest,
            openTaskId = openTaskId,
            onLogout = {
                viewModel.onSignedOut()
                scope.launch {
                    runCatching {
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    }
                }
            }
        )
    }
}

@Composable
private fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onGoogleSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Inicia sesion", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onGoogleSignInClick, enabled = !isLoading) {
            Text("Entrar con Google")
        }
        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HomeScreen(
    taskUiState: TaskUiState,
    onAddTask: (
        String,
        TaskPriority,
        Timestamp?,
        String?,
        (Result<Unit>) -> Unit
    ) -> Unit,
    onCreateTag: (String, (Result<com.todolist.app.data.model.Tag>) -> Unit) -> Unit,
    onRenameTag: (com.todolist.app.data.model.Tag, String, (Result<Unit>) -> Unit) -> Unit,
    onDeleteTag: (com.todolist.app.data.model.Tag, (Result<Unit>) -> Unit) -> Unit,
    onClearCreateError: () -> Unit,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onSaveTask: (Task, String, TaskPriority, Timestamp?, String?) -> Unit,
    openCreateRequest: Int,
    openTaskRequest: Int,
    openTaskId: String?,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var openFilterRequest by remember { mutableStateOf(0) }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Tareas", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openFilterRequest += 1 }) {
                    Icon(imageVector = Icons.Filled.FilterList, contentDescription = "Filtrar")
                }
                Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Salir") },
                        onClick = {
                            menuExpanded = false
                            onLogout()
                        }
                    )
                }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TaskScreen(
            tags = taskUiState.tags,
            openTasks = taskUiState.openTasks,
            doneTasks = taskUiState.doneTasks,
            archivedTasks = taskUiState.archivedTasks,
            isLoading = taskUiState.isLoading,
            errorMessage = taskUiState.errorMessage,
            onAddTask = onAddTask,
            onCreateTag = onCreateTag,
            onRenameTag = onRenameTag,
            onDeleteTag = onDeleteTag,
            isCreating = taskUiState.isCreating,
            createError = taskUiState.createError,
            onClearCreateError = onClearCreateError,
            onToggleCompleted = onToggleCompleted,
            onChangePriority = onChangePriority,
            onToggleArchived = onToggleArchived,
            onDeleteTask = onDeleteTask,
            onSaveTask = onSaveTask,
            hasSession = taskUiState.user != null,
            openFilterRequest = openFilterRequest,
            openCreateRequest = openCreateRequest,
            openTaskRequest = openTaskRequest,
            openTaskId = openTaskId,
            modifier = Modifier.weight(1f)
        )
    }
}

private suspend fun signInWithGoogle(
    auth: FirebaseAuth,
    credentialManager: CredentialManager,
    serverClientId: String,
    context: Context
) {
    val googleOption = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleOption)
        .build()

    val credentialResponse = credentialManager.getCredential(context, request)
    val credential = credentialResponse.credential

    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
        auth.signInWithCredential(firebaseCredential).await()
        return
    }

    throw IllegalStateException("Credencial de Google no valida.")
}

private fun mapSignInError(throwable: Throwable): String {
    return when (throwable) {
        is GetCredentialCancellationException -> "Inicio de sesion cancelado."
        is GoogleIdTokenParsingException -> "No se pudo leer la credencial de Google."
        is GetCredentialException -> throwable.message ?: "Error al iniciar sesion con Google."
        else -> throwable.message ?: "Error inesperado al iniciar sesion."
    }
}
