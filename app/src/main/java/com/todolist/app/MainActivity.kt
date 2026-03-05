package com.todolist.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    private val authViewModel: AuthViewModel by viewModels()
    private val taskViewModel: TaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TodoListTheme {
                AuthGate(
                    viewModel = authViewModel,
                    taskViewModel = taskViewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun AuthGate(
    viewModel: AuthViewModel,
    taskViewModel: TaskViewModel,
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
            email = uiState.user?.email ?: "No email",
            uid = uiState.user?.uid.orEmpty(),
            taskUiState = taskUiState,
            modifier = modifier,
            onAddTask = taskViewModel::addTask,
            onToggleCompleted = taskViewModel::toggleCompleted,
            onChangePriority = taskViewModel::changePriority,
            onToggleArchived = taskViewModel::toggleArchived,
            onDeleteTask = taskViewModel::deleteTask,
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
    email: String,
    uid: String,
    taskUiState: TaskUiState,
    onAddTask: (String, TaskPriority, Timestamp?, String) -> Unit,
    onToggleCompleted: (Task) -> Unit,
    onChangePriority: (Task, TaskPriority) -> Unit,
    onToggleArchived: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "Home", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Email: $email")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "UID: $uid")
        Spacer(modifier = Modifier.height(16.dp))
        TaskScreen(
            openTasks = taskUiState.openTasks,
            archivedTasks = taskUiState.archivedTasks,
            isLoading = taskUiState.isLoading,
            errorMessage = taskUiState.errorMessage,
            onAddTask = onAddTask,
            onToggleCompleted = onToggleCompleted,
            onChangePriority = onChangePriority,
            onToggleArchived = onToggleArchived,
            onDeleteTask = onDeleteTask,
            modifier = Modifier
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onLogout) {
            Text("Salir")
        }
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
