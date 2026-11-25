package com.dfalling.tareas.data

import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Typed failures on top of [IOException] (which propagates as itself = "network unavailable"). */
sealed class ApiException(message: String) : Exception(message) {
    /** 401 — token missing, invalid, or revoked; the UI shows the login screen again. */
    class Unauthorized(message: String) : ApiException(message)

    /** 422 — the server rejected a field value ("title is too long", ...). */
    class Validation(message: String) : ApiException(message)

    /** Any other non-2xx. 404 on PATCH means the server already hard-deleted the todo. */
    class HttpError(val code: Int, message: String) : ApiException("$code: $message") {
        val isNotFound: Boolean get() = code == 404
    }
}

@kotlinx.serialization.Serializable
private data class ErrorBody(val error: String)

/** Canonical PATCH field names, as the API's JSON spells them (stored in pendingFields). */
object PatchField {
    const val TITLE = "title"
    const val NOTES = "notes"
    const val DUE_DATE = "due_date"
    const val COMPLETED = "completed"
    val ALL = listOf(TITLE, NOTES, DUE_DATE, COMPLETED)
}

/**
 * Builds a PATCH body from a row's pending fields: only requested fields
 * appear (absent = untouched), and notes/due_date are explicit JSON null when
 * the row has no value (null = clear). Field order is canonical regardless of
 * the stored comma order.
 */
fun buildPatchBody(todo: TodoEntity, fields: Collection<String> = todo.pendingFieldSet): JsonObject =
    buildJsonObject {
        for (field in PatchField.ALL) {
            if (field !in fields) continue
            when (field) {
                PatchField.TITLE -> put(PatchField.TITLE, todo.title)
                PatchField.NOTES ->
                    if (todo.notes == null) put(PatchField.NOTES, JsonNull)
                    else put(PatchField.NOTES, todo.notes)
                PatchField.DUE_DATE ->
                    if (todo.dueDateEpochDay == null) put(PatchField.DUE_DATE, JsonNull)
                    else put(PatchField.DUE_DATE, todo.dueDate.toString())
                PatchField.COMPLETED -> put(PatchField.COMPLETED, todo.completed)
            }
        }
    }

/**
 * Hand-rolled client for the six tareas /api/v1 endpoints (PLAN.md Appendix A).
 * Auth is a per-call bearer token because the server URL and token live in
 * [SettingsRepository] and change across logins.
 */
class TareasApi(httpClient: OkHttpClient? = null) {
    private val http =
        httpClient
            ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** POST /auth/login → the bearer token. 401 = bad credentials; 429 = rate limited. */
    suspend fun login(serverUrl: String, email: String, password: String): String {
        val request =
            Request.Builder()
                .url(url(serverUrl, "auth/login"))
                .post(
                    ApiJson
                        .encodeToString(LoginRequest(email = email.trim(), password = password))
                        .toRequestBody(jsonMediaType),
                )
                .build()
        return execute(request) { ApiJson.decodeFromString<LoginResponse>(it).token }
    }

    /** POST /auth/logout — revokes the presented token; idempotent on the server. */
    suspend fun logout(serverUrl: String, token: String) {
        val request =
            Request.Builder()
                .url(url(serverUrl, "auth/logout"))
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody())
                .build()
        execute(request) { /* 204, no body */ }
    }

    /** GET /todos?includeCompleted=true — the full list the sync pull merges. */
    suspend fun listTodos(serverUrl: String, token: String): List<TodoJson> {
        val request =
            Request.Builder()
                .url(url(serverUrl, "todos") + "?includeCompleted=true")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        return execute(request) { ApiJson.decodeFromString<List<TodoJson>>(it) }
    }

    /** POST /todos → 201 + the created todo (absent notes/due_date = "not provided"). */
    suspend fun createTodo(
        serverUrl: String,
        token: String,
        title: String,
        notes: String?,
        dueDate: LocalDate?,
    ): TodoJson {
        val body = buildJsonObject {
            put("title", title)
            if (notes != null) put("notes", notes)
            if (dueDate != null) put("due_date", dueDate.toString())
        }
        val request =
            Request.Builder()
                .url(url(serverUrl, "todos"))
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        return execute(request) { ApiJson.decodeFromString<TodoJson>(it) }
    }

    /** PATCH /todos/{id} with exactly [body]'s fields (absent = untouched, null = clear). */
    suspend fun patchTodo(serverUrl: String, token: String, id: Long, body: JsonObject): TodoJson {
        val request =
            Request.Builder()
                .url(url(serverUrl, "todos/$id"))
                .header("Authorization", "Bearer $token")
                .patch(body.toString().toRequestBody(jsonMediaType))
                .build()
        return execute(request) { ApiJson.decodeFromString<TodoJson>(it) }
    }

    /**
     * DELETE /todos/{id}. Deletion is idempotent from our side: 404 counts as
     * deleted (the server hard-deletes immediately, incl. the auto-delete purge).
     */
    suspend fun deleteTodo(serverUrl: String, token: String, id: Long) {
        val request =
            Request.Builder()
                .url(url(serverUrl, "todos/$id"))
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        try {
            execute(request) { /* 204, no body */ }
        } catch (e: ApiException.HttpError) {
            if (!e.isNotFound) throw e
        }
    }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val text =
                try {
                    http.newCall(request).execute().use { response ->
                        val bodyText = response.body.string()
                        when {
                            response.code == 401 ->
                                throw ApiException.Unauthorized(errorMessage(bodyText))
                            response.code == 422 -> throw ApiException.Validation(errorMessage(bodyText))
                            !response.isSuccessful ->
                                throw ApiException.HttpError(response.code, errorMessage(bodyText))
                        }
                        bodyText
                    }
                } catch (e: IOException) {
                    throw e
                }
            try {
                parse(text)
            } catch (e: SerializationException) {
                throw ApiException.HttpError(-1, "malformed response body")
            }
        }

    /** Base URL + /api/v1/ + path, tolerating a trailing slash on the stored URL. */
    private fun url(serverUrl: String, path: String): String =
        "${serverUrl.trim().trimEnd('/')}/api/v1/$path"

    /** Pulls the server's {"error":"..."} message out of an error body. */
    private fun errorMessage(bodyText: String): String =
        runCatching { ApiJson.decodeFromString<ErrorBody>(bodyText).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: bodyText.trim().take(200).ifBlank { "request failed" }
}
