package com.dfalling.tareas.sync

import com.dfalling.tareas.R
import com.dfalling.tareas.UiText
import com.dfalling.tareas.data.ApiException
import com.dfalling.tareas.data.PatchField
import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.TodoApi
import com.dfalling.tareas.data.TodoDao
import com.dfalling.tareas.data.TodoEntity
import com.dfalling.tareas.data.TodoJson
import com.dfalling.tareas.data.buildPatchBody
import com.dfalling.tareas.data.overwrite
import com.dfalling.tareas.data.parseApiDate
import com.dfalling.tareas.data.parseApiTimestamp
import com.dfalling.tareas.data.toEntity
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sync engine (PLAN.md §5): push the pending-op queue oldest-first, then
 * pull the full server list and merge it into Room. Local pending rows win the
 * merge; the server wins everything else; server deletions always win.
 *
 * One cycle at a time: extra triggers while a cycle runs just make it run once
 * more afterwards ([reRun]), so rapid mutations don't stack duplicate pulls.
 *
 * [rowMutex] is shared with the mutation repository and held only around
 * read-modify-write blocks, so a mutation can never be clobbered by a cycle
 * write landing on a stale snapshot.
 */
class SyncCoordinator(
    private val dao: TodoDao,
    private val api: TodoApi,
    private val rowMutex: Mutex = Mutex(),
    /** (serverUrl, token) or null when logged out. */
    private val credentials: suspend () -> Pair<String, String>?,
    /** Persists the "last full sync" timestamp (DataStore) on a clean cycle. */
    private val onSynced: suspend (epochMs: Long) -> Unit = {},
    /** Arms the WorkManager safety net; a no-op lambda in unit tests. */
    private val enqueueBackground: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    scopeContext: CoroutineContext = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + scopeContext)
    private val mutex = Mutex()

    @Volatile
    private var reRun = false

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Fire-and-forget trigger for mutations and app start: also arms the
     * constrained WorkManager work so a mutation made offline syncs on its own
     * once connectivity returns.
     */
    fun requestSync() {
        enqueueBackground()
        scope.launch { runNow() }
    }

    /**
     * Runs cycles until quiescent and returns the resulting state. Callers who
     * want to await a sync (WorkManager, pull-to-refresh) use this directly.
     */
    suspend fun runNow(): SyncState {
        if (!mutex.tryLock()) {
            // A cycle is already running; ask it to go around once more so the
            // fresh mutations (or ours, once it ends) get their own cycle.
            reRun = true
            return _state.value
        }
        try {
            do {
                reRun = false
                runCatching { cycle() }
                    .onFailure { e ->
                        // Anything unexpected (a bad row, a parse surprise) must not
                        // kill the app or wedge the queue: surface and leave the
                        // queue for the next trigger.
                        _state.value = SyncState.Error(UiText(R.string.sync_error_server, e.message ?: "unexpected error"))
                    }
            } while (reRun)
        } finally {
            mutex.unlock()
        }
        return _state.value
    }

    /** One push→pull pair (PLAN.md §5). Always ends in a definitive [SyncState]. */
    private suspend fun cycle() {
        _state.value = SyncState.Syncing
        val (serverUrl, token) =
            credentials() ?: run {
                _state.value = SyncState.NeedsAuth
                return
            }

        // 1. Push the queue, oldest first (dao.pending() is already ordered).
        var validationError: String? = null
        for (row in dao.pending()) {
            try {
                push(serverUrl, token, row)
            } catch (e: ApiException.Unauthorized) {
                _state.value = SyncState.NeedsAuth
                return
            } catch (e: ApiException.Validation) {
                // The server will never accept this row (limits changed, older
                // server…). Clearing its pending state lets the queue move; the
                // pull's server-wins merge then converges the row locally.
                validationError = e.message
                rowMutex.withLock {
                    dao.get(row.localId)?.let { dao.upsert(it.copy(pendingOp = PendingOp.NONE, pendingFields = "")) }
                }
            } catch (e: ApiException.HttpError) {
                _state.value = SyncState.Error(UiText(R.string.sync_error_server, e.message ?: "request failed"))
                return
            } catch (e: IOException) {
                _state.value = SyncState.Error(UiText(R.string.sync_error_network))
                return
            }
        }
        // Rows that became pending mid-cycle (mutations, create-ack remnants like
        // a completion a POST couldn't carry) still owe the server a request.
        if (validationError == null && dao.pending().isNotEmpty()) reRun = true

        // 2. Pull & merge.
        val server =
            try {
                api.listTodos(serverUrl, token)
            } catch (e: ApiException.Unauthorized) {
                _state.value = SyncState.NeedsAuth
                return
            } catch (e: ApiException.Validation) {
                _state.value = SyncState.Error(UiText(R.string.sync_error_validation, e.message ?: "invalid response"))
                return
            } catch (e: ApiException.HttpError) {
                _state.value = SyncState.Error(UiText(R.string.sync_error_server, e.message ?: "request failed"))
                return
            } catch (e: IOException) {
                _state.value = SyncState.Error(UiText(R.string.sync_error_network))
                return
            }

        rowMutex.withLock {
            val local = dao.getAll()
            val merged = mergePull(local, server)
            val localById = local.associateBy { it.localId }
            dao.upsertAll(merged.filter { it != localById[it.localId] })
            val keepIds = merged.mapTo(HashSet()) { it.localId }
            for (row in local) {
                if (row.localId !in keepIds) dao.delete(row.localId)
            }
        }

        if (validationError != null) {
            _state.value = SyncState.Error(UiText(R.string.sync_error_validation, validationError))
            return
        }
        onSynced(clock())
        _state.value = SyncState.Idle
    }

    private suspend fun push(serverUrl: String, token: String, row: TodoEntity) {
        when (row.pendingOp) {
            PendingOp.CREATE -> {
                val ack = api.createTodo(serverUrl, token, row.title, row.notes, row.dueDate)
                reconcileUnderLock(row.localId) { reconcileWithAck(it, ack) }
            }
            PendingOp.UPDATE -> {
                val ack =
                    try {
                        api.patchTodo(serverUrl, token, row.serverId!!, buildPatchBody(row))
                    } catch (e: ApiException.HttpError) {
                        if (!e.isNotFound) throw e
                        // The server no longer has it (web deletion or the hourly
                        // auto-delete purge): deletions always win — drop the row
                        // and let the cycle continue with the rest of the queue.
                        rowMutex.withLock { dao.delete(row.localId) }
                        return
                    }
                reconcileUnderLock(row.localId) { reconcileWithAck(it, ack) }
            }
            PendingOp.DELETE -> {
                // 204 or 404 both mean gone — the API layer swallows the 404.
                api.deleteTodo(serverUrl, token, row.serverId!!)
                rowMutex.withLock { dao.delete(row.localId) }
            }
            PendingOp.NONE -> Unit // unreachable: the queue only holds dirty rows
        }
    }

    private suspend fun reconcileUnderLock(localId: String, reconcile: (TodoEntity) -> TodoEntity) {
        rowMutex.withLock {
            val current = dao.get(localId) ?: return@withLock // vanished mid-cycle (e.g. CREATE + delete): nothing to write
            dao.upsert(reconcile(current))
        }
    }

    companion object {
        /**
         * Merges a pulled server list into the local rows (pure, unit-tested).
         *
         * - Local pending rows always win, including pending DELETEs (kept so the
         *   next cycle's push can deliver them).
         * - A local row whose serverId is no longer in the list was deleted on the
         *   server → removed locally.
         * - Server rows with no local match are inserted fresh (always synced).
         *
         * Order is deterministic (pending-create locals first, then server order)
         * but meaningless — the UI re-groups via `Tabs`.
         */
        fun mergePull(local: List<TodoEntity>, server: List<TodoJson>): List<TodoEntity> {
            val byServerId = local.filter { it.serverId != null }.associateBy { it.serverId }
            val result = mutableListOf<TodoEntity>()

            // Rows the server doesn't know: offline creates (kept for the push)
            // stay; everything else absent from the list was deleted server-side.
            for (row in local) {
                if (row.serverId == null) result += row
            }
            for (item in server) {
                val existing = byServerId[item.id]
                result +=
                    when {
                        existing == null -> item.toEntity()
                        existing.isPending -> existing
                        else -> item.overwrite(existing)
                    }
            }
            return result
        }

        /**
         * A successful CREATE/PATCH ack → the row's next state. Adopts the ack's
         * identity and stamps; the fields still pending afterwards are exactly
         * those where the row's *current* values disagree with the ack (a
         * mutation landed mid-flight — a queued edit must survive the race; a
         * `completed` a POST couldn't carry shows up the same way and rides the
         * next PATCH). Everything the ack confirms is cleared — including
         * previously pending fields, since the request already carried them.
         */
        internal fun reconcileWithAck(current: TodoEntity, ack: TodoJson): TodoEntity {
            if (current.pendingOp == PendingOp.DELETE) return current // a queued delete wins over the acked values

            val ackDueDay = ack.dueDate?.let(::parseApiDate)
            val pending = mutableSetOf<String>()

            var title = current.title
            var notes = current.notes
            var dueDay = current.dueDateEpochDay
            var completed = current.completed
            var completedAt = current.completedAtEpochMs
            if (title != ack.title) pending += PatchField.TITLE else title = ack.title
            if (notes != ack.notes) pending += PatchField.NOTES else notes = ack.notes
            if (dueDay != ackDueDay) pending += PatchField.DUE_DATE else dueDay = ackDueDay
            if (completed != ack.completed) {
                pending += PatchField.COMPLETED
            } else {
                completed = ack.completed
                completedAt = ack.completedAt?.let(::parseApiTimestamp)
            }

            return current.copy(
                serverId = ack.id,
                createdAtEpochMs = parseApiTimestamp(ack.createdAt),
                updatedAtEpochMs = parseApiTimestamp(ack.updatedAt),
                title = title,
                notes = notes,
                dueDateEpochDay = dueDay,
                completed = completed,
                completedAtEpochMs = completedAt,
                pendingOp = if (pending.isEmpty()) PendingOp.NONE else PendingOp.UPDATE,
                pendingFields = PatchField.ALL.filter { it in pending }.joinToString(","),
            )
        }
    }
}
