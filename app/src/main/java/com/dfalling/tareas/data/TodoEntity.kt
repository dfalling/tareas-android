package com.dfalling.tareas.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.LocalDate

/** What a row still owes the server; NONE means fully synced. */
enum class PendingOp {
    NONE,

    /** Created offline — POST the full state on sync (pendingFields is unused). */
    CREATE,

    /** Edited offline — PATCH only the fields in [TodoEntity.pendingFields]. */
    UPDATE,

    /** Deleted offline — kept in the table until the DELETE lands, then gone. */
    DELETE,
}

/** Room converter so rows store the enum as its name (TEXT: NONE/CREATE/UPDATE/DELETE). */
class PendingOpConverter {
    @TypeConverter
    fun toName(op: PendingOp): String = op.name

    @TypeConverter
    fun fromName(name: String): PendingOp =
        PendingOp.entries.firstOrNull { it.name == name } ?: PendingOp.NONE
}

/**
 * The single local table — the source of truth the UI reads and the sync
 * engine maintains. See PLAN.md §4 for the pending-op collapsing rules.
 */
@Entity(tableName = "todos")
data class TodoEntity(
    /** Device-generated UUID; rows exist here before the server knows them. */
    @PrimaryKey val localId: String,
    /** Server id; null until a CREATE is acknowledged. */
    val serverId: Long?,
    val title: String,
    /** null = no notes. */
    val notes: String?,
    /** null = unscheduled; [LocalDate.toEpochDay]. */
    val dueDateEpochDay: Long?,
    val completed: Boolean,
    /** null until completed; cleared on reopen. */
    val completedAtEpochMs: Long?,
    /** Server clock for synced rows, local clock while a CREATE is pending. */
    val createdAtEpochMs: Long,
    /** Server updatedAt for synced rows, local clock while pending. */
    val updatedAtEpochMs: Long,
    val pendingOp: PendingOp,
    /** Comma list of [PatchField] names for UPDATE, "" otherwise, e.g. "title,due_date". */
    val pendingFields: String,
) {
    val isPending: Boolean get() = pendingOp != PendingOp.NONE

    /** null = unscheduled. */
    val dueDate: LocalDate?
        get() = dueDateEpochDay?.let(LocalDate::ofEpochDay)

    /** The alphabetical sort's id tiebreak (PLAN.md §6): unsynced creates sort last. */
    val sortId: Long
        get() = serverId ?: Long.MAX_VALUE

    /** The stored comma list as a set, e.g. "title,due_date" → {title, due_date}. */
    val pendingFieldSet: Set<String>
        get() = pendingFields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
