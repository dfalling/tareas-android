package com.dfalling.tareas.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dfalling.tareas.R
import com.dfalling.tareas.TareasApp
import com.dfalling.tareas.UiText
import com.dfalling.tareas.data.ApiException
import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.Settings
import com.dfalling.tareas.data.SettingsRepository
import com.dfalling.tareas.data.TareasApi
import com.dfalling.tareas.data.TodoDao
import com.dfalling.tareas.data.TodoEntity
import com.dfalling.tareas.data.TodoRepository
import com.dfalling.tareas.sync.SyncCoordinator
import com.dfalling.tareas.sync.SyncState
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The UI's single entry point to the app: observes Room and DataStore,
 * forwards mutations to the local-first [TodoRepository], and surfaces sync
 * state and one-shot messages (snackbars). Screens stay stateless.
 */
class MainViewModel(
    private val settingsRepo: SettingsRepository,
    private val dao: TodoDao,
    private val todosRepo: TodoRepository,
    private val api: TareasApi,
    private val sync: SyncCoordinator,
) : ViewModel() {
    val settings: StateFlow<Settings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Live rows, minus queued deletes (PLAN.md §4: a pending-delete row is out
     * of the UI until the push lands, then it just disappears).
     */
    val todos: StateFlow<List<TodoEntity>> =
        dao.observeAll()
            .map { rows -> rows.filterNot { it.pendingOp == PendingOp.DELETE } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncState: StateFlow<SyncState> = sync.state

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Login screen: re-requesting while a sign-in is in flight would double-submit. */
    var signingIn by mutableStateOf(false)
        private set

    /**
     * Logs in, stores the token (serverUrl/email kept for the next prefill), and
     * kicks a sync; returns the mapped error, or null on success. Mapping mirrors
     * the API's error surface (Appendix A): 401 bad credentials, 429 rate limit
     * (10 attempts / 5 min), IOException unreachable server.
     */
    suspend fun signIn(serverUrl: String, email: String, password: String): UiText? {
        signingIn = true
        try {
            val token = api.login(serverUrl, email, password)
            settingsRepo.saveLogin(serverUrl, email, token)
            sync.requestSync()
            return null
        } catch (e: ApiException.Unauthorized) {
            return UiText(R.string.login_error_credentials)
        } catch (e: ApiException.Validation) {
            return UiText(R.string.login_error_failed, e.message ?: "rejected")
        } catch (e: ApiException.HttpError) {
            return if (e.code == 429) UiText(R.string.login_error_rate_limited)
            else UiText(R.string.login_error_failed, e.message ?: "request failed")
        } catch (e: IOException) {
            return UiText(R.string.login_error_network)
        } finally {
            signingIn = false
        }
    }

    /**
     * Revokes the token on the server (best-effort — a failed revoke must not
     * keep the app "logged in" locally) and clears it from DataStore. Room data
     * stays, and the server/email prefill the login screen again.
     */
    fun logout() {
        viewModelScope.launch {
            try {
                settingsRepo.credentials()?.let { (serverUrl, token) -> api.logout(serverUrl, token) }
            } catch (ignored: Exception) {
            }
            settingsRepo.clearToken()
        }
    }

    /** Manual sync (pull-to-refresh); the resulting [SyncState] drives the top bar. */
    suspend fun syncNow(): SyncState = sync.runNow()

    fun create(title: String, notes: String?, dueDate: LocalDate?) {
        viewModelScope.launch { todosRepo.create(title, notes, dueDate) }
    }

    fun save(localId: String, title: String, notes: String?, dueDate: LocalDate?) {
        viewModelScope.launch { todosRepo.edit(localId, title, notes, dueDate) }
    }

    fun setCompleted(localId: String, completed: Boolean) {
        viewModelScope.launch { todosRepo.setCompleted(localId, completed) }
    }

    /** Permanent (tareas hard-deletes); confirmed by the caller, acknowledged here. */
    fun delete(todo: TodoEntity) {
        viewModelScope.launch {
            todosRepo.delete(todo.localId)
            _messages.send(UiText(R.string.deleted_todo, todo.title))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TareasApp
                    MainViewModel(
                        settingsRepo = app.settings,
                        dao = app.database.todos(),
                        todosRepo = app.todos,
                        api = app.api,
                        sync = app.sync,
                    )
                }
            }
    }
}
