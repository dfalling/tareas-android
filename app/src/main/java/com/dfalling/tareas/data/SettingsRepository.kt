package com.dfalling.tareas.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the app remembers between runs; the token's presence, never its value. */
data class Settings(
    /** Server base URL, normalized (no trailing slash); null before first login. */
    val serverUrl: String?,
    val email: String?,
    /** Epoch millis of the last completed sync; null = never synced. */
    val lastSyncedAt: Long?,
    val hasToken: Boolean,
)

private val Context.dataStore by preferencesDataStore(name = "tareas_settings")

class SettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val email = stringPreferencesKey("email")
        val lastSyncedAt = longPreferencesKey("last_synced_at")
        val token = stringPreferencesKey("token_encrypted")
    }

    val settings: Flow<Settings> =
        context.dataStore.data.map { prefs ->
            Settings(
                serverUrl = prefs[Keys.serverUrl],
                email = prefs[Keys.email],
                lastSyncedAt = prefs[Keys.lastSyncedAt],
                hasToken = prefs[Keys.token] != null,
            )
        }

    /** Decrypts the bearer token; null if never logged in or the blob is undecryptable. */
    suspend fun token(): String? =
        context.dataStore.data.first()[Keys.token]?.let { encrypted -> TokenCipher.decrypt(encrypted) }

    /** (serverUrl, token) for the sync engine; null when logged out or undecryptable. */
    suspend fun credentials(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val serverUrl = prefs[Keys.serverUrl] ?: return null
        return prefs[Keys.token]
            ?.let { encrypted -> TokenCipher.decrypt(encrypted) }
            ?.let { token -> serverUrl to token }
    }

    /** Stores a successful login. lastSyncedAt is untouched: a 401 re-login keeps history. */
    suspend fun saveLogin(serverUrl: String, email: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.serverUrl] = serverUrl.trim().trimEnd('/')
            prefs[Keys.email] = email.trim()
            prefs[Keys.token] = TokenCipher.encrypt(token)
        }
    }

    suspend fun setLastSyncedAt(epochMs: Long) {
        context.dataStore.edit { it[Keys.lastSyncedAt] = epochMs }
    }

    /** Clears only the token (logout / 401); server URL and email stay for the pre-filled login. */
    suspend fun clearToken() {
        context.dataStore.edit { it.remove(Keys.token) }
    }
}
