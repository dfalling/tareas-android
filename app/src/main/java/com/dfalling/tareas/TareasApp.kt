package com.dfalling.tareas

import android.app.Application
import com.dfalling.tareas.data.AppDatabase
import com.dfalling.tareas.data.SettingsRepository
import com.dfalling.tareas.data.TareasApi
import com.dfalling.tareas.data.TodoRepository
import com.dfalling.tareas.sync.SyncCoordinator
import com.dfalling.tareas.sync.SyncWorker
import kotlinx.coroutines.sync.Mutex

/** Manual DI root (no Hilt): lazy singletons, constructed on first touch. */
class TareasApp : Application() {
    /** Shared by mutations and the sync engine: row read-modify-write blocks. */
    private val rowMutex = Mutex()

    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val api: TareasApi by lazy { TareasApi() }

    val sync: SyncCoordinator by lazy {
        SyncCoordinator(
            dao = database.todos(),
            api = api,
            rowMutex = rowMutex,
            credentials = { settings.credentials() },
            onSynced = { settings.setLastSyncedAt(it) },
            enqueueBackground = { SyncWorker.enqueue(this) },
        )
    }

    val todos: TodoRepository by lazy {
        TodoRepository(
            dao = database.todos(),
            rowMutex = rowMutex,
            onMutated = { sync.requestSync() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // App-start sync (PLAN.md §5): an immediate cycle when possible, plus
        // the WorkManager net that also covers the offline / first-pull cases.
        sync.requestSync()
    }
}
