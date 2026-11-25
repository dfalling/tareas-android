package com.dfalling.tareas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dfalling.tareas.sync.SyncState
import com.dfalling.tareas.ui.LoginScreen
import com.dfalling.tareas.ui.MainScreen
import com.dfalling.tareas.ui.MainViewModel
import com.dfalling.tareas.ui.theme.TareasTheme

/**
 * Two screens, no navigation library (PLAN.md §3): the login screen shows while
 * there is no token (fresh install, logout) or the sync engine reported a 401;
 * otherwise the main tabbed list.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TareasTheme {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val syncState by viewModel.syncState.collectAsStateWithLifecycle()
                val current = settings
                when {
                    // DataStore's first read hasn't landed yet — flash nothing.
                    current == null -> Unit
                    current.hasToken && syncState !is SyncState.NeedsAuth -> MainScreen(viewModel)
                    else -> LoginScreen(viewModel, current)
                }
            }
        }
    }
}
