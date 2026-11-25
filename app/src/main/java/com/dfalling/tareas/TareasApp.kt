package com.dfalling.tareas

import android.app.Application
import com.dfalling.tareas.data.AppDatabase
import com.dfalling.tareas.data.SettingsRepository
import com.dfalling.tareas.data.TareasApi

/** Manual DI root (no Hilt): lazy singletons, constructed on first touch. */
class TareasApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val api: TareasApi by lazy { TareasApi() }
}
