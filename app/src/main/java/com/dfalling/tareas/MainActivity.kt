package com.dfalling.tareas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dfalling.tareas.data.ApiException
import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.Settings
import com.dfalling.tareas.sync.SyncState
import com.dfalling.tareas.ui.theme.TareasTheme
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Temporary harness for Phase 3's acceptance check (offline mutations must
 * reach the server once the device is back online): minimal login form and a
 * bare-bones list with local-first mutations. The real UI replaces this in
 * Phase 4 — don't build on it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TareasApp
        enableEdgeToEdge()
        setContent {
            TareasTheme {
                val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                val syncState by app.sync.state.collectAsStateWithLifecycle()
                val current = settings
                when {
                    current == null -> Unit // DataStore's first read hasn't landed yet
                    current.hasToken && syncState !is SyncState.NeedsAuth -> TodoListHarness(app, syncState)
                    else -> LoginHarness(app, current)
                }
            }
        }
    }
}

@Composable
private fun LoginHarness(app: TareasApp, settings: Settings) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(settings.serverUrl ?: "http://10.0.2.2:8080") }
    var email by remember { mutableStateOf(settings.email ?: "") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Tareas", fontSize = 28.sp)
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(text = it.asString(), color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    error = signIn(app, serverUrl, email, password)
                    busy = false
                }
            },
        ) {
            Text(if (busy) "Signing in…" else "Sign in")
        }
    }
}

private suspend fun signIn(app: TareasApp, serverUrl: String, email: String, password: String): UiText? =
    try {
        val token = app.api.login(serverUrl, email, password)
        app.settings.saveLogin(serverUrl, email, token)
        app.sync.requestSync()
        null
    } catch (e: ApiException.Unauthorized) {
        UiText(R.string.login_error_credentials)
    } catch (e: ApiException.HttpError) {
        if (e.code == 429) {
            UiText(R.string.login_error_rate_limited)
        } else {
            UiText(R.string.login_error_failed, e.message ?: "request failed")
        }
    } catch (e: ApiException.Validation) {
        UiText(R.string.login_error_failed, e.message ?: "rejected")
    } catch (e: IOException) {
        UiText(R.string.login_error_network)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoListHarness(app: TareasApp, syncState: SyncState) {
    val todos by app.database.todos().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var newTitle by remember { mutableStateOf("") }

    // Harness ordering only: queued deletes last, open first, creation order.
    val rows =
        todos.sortedWith(
            compareBy({ it.pendingOp == PendingOp.DELETE }, { it.completed }, { it.createdAtEpochMs }),
        )

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Text(text = "Tareas", fontSize = 24.sp)
        Text(
            text = syncLabel(syncState, settings?.lastSyncedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("New todo") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = newTitle.isNotBlank(),
                onClick = {
                    val title = newTitle
                    newTitle = ""
                    scope.launch { app.todos.create(title, notes = null, dueDate = null) }
                },
            ) {
                Text("Add")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch { app.sync.runNow() } }) { Text("Sync now") }
            TextButton(
                onClick = {
                    scope.launch {
                        // Best-effort revoke, then drop the token (Room data stays).
                        try {
                            val (url, token) = app.settings.credentials() ?: throw IOException("logged out")
                            app.api.logout(url, token)
                        } catch (ignored: Exception) {
                        }
                        app.settings.clearToken()
                    }
                },
            ) {
                Text("Log out")
            }
        }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Text(text = "No todos yet", modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rows, key = { it.localId }) { todo ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                // Tap toggles completion, long-press deletes (no confirm
                                // in the harness — the real UI adds the dialog).
                                onClick = { scope.launch { app.todos.setCompleted(todo.localId, !todo.completed) } },
                                onLongClick = { scope.launch { app.todos.delete(todo.localId) } },
                            )
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = todo.title,
                            textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
                            color =
                                if (todo.completed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        val details = buildList {
                            todo.dueDate?.let { add(DateTimeFormatter.ofPattern("dd-MM-yy").format(it)) }
                            if (todo.isPending) add("pending sync · ${todo.pendingOp.name}")
                        }
                        if (details.isNotEmpty()) {
                            Text(
                                text = details.joinToString(" · "),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (todo.completed) {
                        Text(text = "✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun syncLabel(state: SyncState, lastSyncedAt: Long?): String =
    when (state) {
        SyncState.Syncing -> stringResource(R.string.sync_state_syncing)
        SyncState.NeedsAuth -> stringResource(R.string.sync_state_needs_auth)
        is SyncState.Error -> state.reason.asString()
        SyncState.Idle ->
            lastSyncedAt
                ?.let { "Synced " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                ?: stringResource(R.string.sync_state_idle)
    }
