package com.dfalling.tareas.data

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local-first mutations (PLAN.md §4): every change lands in Room instantly with
 * pending metadata, then fires [onMutated] (wired to the sync engine's
 * requestSync) so the change reaches the server as soon as possible.
 *
 * All read-modify-write blocks hold [rowMutex], shared with the sync engine, so
 * a cycle write can never land on a stale snapshot and clobber a mutation.
 *
 * Pending-op collapsing (PLAN.md §4):
 * - CREATE + edits → still CREATE (the POST sends full state);
 * - UPDATE + edits → UPDATE, pendingFields accumulate;
 * - CREATE + delete → row removed (the server never saw it);
 * - NONE/UPDATE + delete → DELETE;
 * - DELETE + anything → ignored (the row is already leaving).
 */
class TodoRepository(
    private val dao: TodoDao,
    private val rowMutex: Mutex = Mutex(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMutated: () -> Unit = {},
) {
    /**
     * Creates a todo offline-first: device UUID as identity, local stamps, the
     * whole row queued as a CREATE.
     */
    suspend fun create(title: String, notes: String?, dueDate: LocalDate?): TodoEntity {
        val now = clock()
        val row = TodoEntity(
            localId = UUID.randomUUID().toString(),
            serverId = null,
            title = title.trim(),
            notes = normalized(notes),
            dueDateEpochDay = dueDate?.toEpochDay(),
            completed = false,
            completedAtEpochMs = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            pendingOp = PendingOp.CREATE,
            pendingFields = "",
        )
        rowMutex.withLock { dao.upsert(row) }
        onMutated()
        return row
    }

    /**
     * Replaces title, notes and due date (the editor sheet's save — all three
     * always provided; null notes means "no notes"). pendingFields record only
     * the fields whose value actually changed.
     */
    suspend fun edit(localId: String, title: String, notes: String?, dueDate: LocalDate?) {
        mutate(localId) { row ->
            val changed = buildSet {
                if (title.trim() != row.title) add(PatchField.TITLE)
                if (normalized(notes) != row.notes) add(PatchField.NOTES)
                if (dueDate?.toEpochDay() != row.dueDateEpochDay) add(PatchField.DUE_DATE)
            }
            collapseEdit(
                row.copy(
                    title = title.trim(),
                    notes = normalized(notes),
                    dueDateEpochDay = dueDate?.toEpochDay(),
                    updatedAtEpochMs = clock(),
                ),
                changed,
            )
        }
    }

    /**
     * Completes or reopens. The completion stamp is local until the next ack
     * refreshes it with the server's own timestamp.
     */
    suspend fun setCompleted(localId: String, completed: Boolean) {
        mutate(localId) { row ->
            if (row.completed == completed) return@mutate null
            collapseEdit(
                row.copy(
                    completed = completed,
                    completedAtEpochMs = if (completed) clock() else null,
                    updatedAtEpochMs = clock(),
                ),
                setOf(PatchField.COMPLETED),
            )
        }
    }

    /**
     * Queues a hard delete (tareas has no trash): synced and edited rows become
     * pending DELETE and stay until the push lands; an unsynced create is
     * dropped immediately.
     */
    suspend fun delete(localId: String) {
        rowMutex.withLock {
            val row = dao.get(localId) ?: return
            when (row.pendingOp) {
                PendingOp.CREATE -> dao.delete(localId)
                PendingOp.DELETE -> return
                PendingOp.NONE, PendingOp.UPDATE ->
                    dao.upsert(row.copy(pendingOp = PendingOp.DELETE, pendingFields = ""))
            }
        }
        onMutated()
    }

    /** Read-modify-write under the row lock; [change] returning null = no-op. */
    private suspend fun mutate(localId: String, change: (TodoEntity) -> TodoEntity?) {
        var changed = false
        rowMutex.withLock {
            val row = dao.get(localId) ?: return
            val next = change(row) ?: return
            dao.upsert(next)
            changed = true
        }
        if (changed) onMutated()
    }

    private fun collapseEdit(edited: TodoEntity, changed: Set<String>): TodoEntity? =
        when (edited.pendingOp) {
            PendingOp.NONE ->
                if (changed.isEmpty()) null
                else edited.copy(pendingOp = PendingOp.UPDATE, pendingFields = join(changed))
            PendingOp.CREATE -> edited // POST carries full state; pendingFields stays unused
            PendingOp.UPDATE ->
                if (changed.isEmpty()) edited
                else edited.copy(pendingFields = join(edited.pendingFieldSet + changed))
            PendingOp.DELETE -> null // out of the UI; the queued delete wins
        }

    /** Server limits: whitespace-only notes are null; titles are trimmed. */
    private fun normalized(notes: String?): String? = notes?.takeIf { it.isNotBlank() }

    private fun join(fields: Set<String>): String = PatchField.ALL.filter { it in fields }.joinToString(",")
}
