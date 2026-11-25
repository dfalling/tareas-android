package com.dfalling.tareas.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire form of a todo — tareas' `todoJson` (PLAN.md Appendix A). */
@Serializable
data class TodoJson(
    val id: Long,
    val title: String,
    val notes: String? = null,
    /** "YYYY-MM-DD" or null — a calendar date, never a timestamp. */
    @SerialName("due_date") val dueDate: String? = null,
    val completed: Boolean = false,
    /** RFC3339 or null; stamped by toggle, cleared on reopen. */
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

/**
 * Tolerant on responses (unknown fields don't break older clients against
 * newer servers); request bodies are built explicitly, never by re-encoding
 * a response DTO, because the server rejects unknown request fields.
 */
@OptIn(ExperimentalSerializationApi::class)
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** RFC3339 instant → epoch millis; tolerant of `Z` and numeric offsets. */
fun parseApiTimestamp(raw: String): Long = OffsetDateTime.parse(raw).toInstant().toEpochMilli()

/** "YYYY-MM-DD" → epoch day, the [TodoEntity.dueDateEpochDay] representation. */
fun parseApiDate(raw: String): Long = LocalDate.parse(raw).toEpochDay()

/** Server JSON → fresh local row (a pull insert). Always born synced. */
fun TodoJson.toEntity(localId: String = UUID.randomUUID().toString()): TodoEntity = TodoEntity(
    localId = localId,
    serverId = id,
    title = title,
    notes = notes,
    dueDateEpochDay = dueDate?.let(::parseApiDate),
    completed = completed,
    completedAtEpochMs = completedAt?.let(::parseApiTimestamp),
    createdAtEpochMs = parseApiTimestamp(createdAt),
    updatedAtEpochMs = parseApiTimestamp(updatedAt),
    pendingOp = PendingOp.NONE,
    pendingFields = "",
)

/**
 * Server JSON → field values over an existing row, keeping its local identity
 * and pending state untouched (callers decide when to clear pending — the
 * sync engine's pending-wins rule lives there).
 */
fun TodoJson.overwrite(entity: TodoEntity): TodoEntity = entity.copy(
    title = title,
    notes = notes,
    dueDateEpochDay = dueDate?.let(::parseApiDate),
    completed = completed,
    completedAtEpochMs = completedAt?.let(::parseApiTimestamp),
    createdAtEpochMs = parseApiTimestamp(createdAt),
    updatedAtEpochMs = parseApiTimestamp(updatedAt),
)
