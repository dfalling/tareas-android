package com.dfalling.tareas.sync

import com.dfalling.tareas.data.ApiException
import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.TodoEntity
import com.dfalling.tareas.data.TodoJson
import com.dfalling.tareas.data.parseApiTimestamp
import com.dfalling.tareas.testhelpers.InMemoryTodoDao
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync engine's decision tables (PLAN.md §5): the pure pull-merge, the push
 * behavior against a scripted fake API (201/200/404/401/network), and the
 * ack-reconciliation that protects mid-flight mutations.
 */
class SyncCoordinatorTest {
    // ------------------------------------------------------------------ fakes

    private class FakeApi : com.dfalling.tareas.data.TodoApi {
        data class CreateCall(val title: String, val notes: String?, val dueDate: LocalDate?)
        data class PatchCall(val id: Long, val body: JsonObject)

        val listCalls = mutableListOf<Int>()
        var serverList: List<TodoJson> = emptyList()
        var onList: () -> List<TodoJson> = { serverList }

        val createCalls = mutableListOf<CreateCall>()
        var onCreate: (CreateCall) -> TodoJson = { error("unexpected create: $it") }

        val patchCalls = mutableListOf<PatchCall>()
        var onPatch: (PatchCall) -> TodoJson = { error("unexpected patch: $it") }

        val deleteCalls = mutableListOf<Long>()
        var onDelete: (Long) -> Unit = {}

        override suspend fun listTodos(serverUrl: String, token: String): List<TodoJson> {
            listCalls += 1
            return onList()
        }

        override suspend fun createTodo(
            serverUrl: String,
            token: String,
            title: String,
            notes: String?,
            dueDate: LocalDate?,
        ): TodoJson {
            val call = CreateCall(title, notes, dueDate)
            createCalls += call
            return onCreate(call)
        }

        override suspend fun patchTodo(serverUrl: String, token: String, id: Long, body: JsonObject): TodoJson {
            val call = PatchCall(id, body)
            patchCalls += call
            return onPatch(call)
        }

        override suspend fun deleteTodo(serverUrl: String, token: String, id: Long) {
            deleteCalls += id
            onDelete(id)
        }
    }

    // --------------------------------------------------------------- builders

    private fun serverTodo(
        id: Long,
        title: String = "Server todo",
        notes: String? = null,
        dueDate: String? = null,
        completed: Boolean = false,
        completedAt: String? = null,
        createdAt: String = "2026-11-20T09:00:00Z",
        updatedAt: String = "2026-11-20T09:00:00Z",
    ) = TodoJson(
        id = id,
        title = title,
        notes = notes,
        dueDate = dueDate,
        completed = completed,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** The server's echo of what we pushed (201/200 body). */
    private fun ack(
        id: Long,
        title: String = "Local todo",
        notes: String? = null,
        dueDate: String? = null,
        completed: Boolean = false,
    ) = serverTodo(
        id = id,
        title = title,
        notes = notes,
        dueDate = dueDate,
        completed = completed,
        completedAt = if (completed) "2026-11-21T10:00:00Z" else null,
        updatedAt = "2026-11-21T10:00:00Z",
    )

    private fun row(
        localId: String = "local-1",
        serverId: Long? = 42L,
        title: String = "Local todo",
        notes: String? = null,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        completedAt: Long? = null,
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        pendingOp: PendingOp = PendingOp.NONE,
        pendingFields: String = "",
    ) = TodoEntity(
        localId = localId,
        serverId = serverId,
        title = title,
        notes = notes,
        dueDateEpochDay = dueDate?.toEpochDay(),
        completed = completed,
        completedAtEpochMs = completedAt,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
        pendingOp = pendingOp,
        pendingFields = pendingFields,
    )

    private var syncedAt: Long? = null

    /** Suspend-free seeding for test setup (dao is per-test, so it's a receiver). */
    private fun InMemoryTodoDao.seed(vararg rows: TodoEntity) {
        runBlocking { rows.forEach { upsert(it) } }
    }

    private fun coordinator(dao: InMemoryTodoDao, api: FakeApi): SyncCoordinator =
        SyncCoordinator(
            dao = dao,
            api = api,
            credentials = { "http://server" to "token" },
            onSynced = { syncedAt = it },
            clock = { 5_000L },
        )

    // ------------------------------------------------------------- mergePull

    @Test
    fun mergePull_insertsServerItemsAsFreshSyncedRows() {
        val server =
            listOf(
                serverTodo(1, "Alpha", notes = "n", dueDate = "2026-11-26"),
                serverTodo(2, "Beta", completed = true, completedAt = "2026-11-21T10:00:00Z"),
            )

        val merged = SyncCoordinator.mergePull(emptyList(), server)

        assertEquals(setOf(1L, 2L), merged.map { it.serverId }.toSet())
        merged.forEach {
            assertEquals(PendingOp.NONE, it.pendingOp)
            assertEquals("", it.pendingFields)
        }
        val alpha = merged.first { it.serverId == 1L }
        assertEquals(LocalDate.of(2026, 11, 26), alpha.dueDate)
        assertEquals("n", alpha.notes)
        assertEquals(parseApiTimestamp("2026-11-20T09:00:00Z"), alpha.createdAtEpochMs)
        val beta = merged.first { it.serverId == 2L }
        assertTrue(beta.completed)
        assertEquals(parseApiTimestamp("2026-11-21T10:00:00Z"), beta.completedAtEpochMs)
    }

    @Test
    fun mergePull_serverWinsOverNonPendingRowsKeepingLocalIdentity() {
        val local = listOf(row(localId = "keep-me", serverId = 42, title = "old title"))
        val server = listOf(serverTodo(42, title = "new title", updatedAt = "2026-11-22T08:00:00Z"))

        val merged = SyncCoordinator.mergePull(local, server)

        val only = merged.single()
        assertEquals("keep-me", only.localId)
        assertEquals("new title", only.title)
        assertEquals(parseApiTimestamp("2026-11-22T08:00:00Z"), only.updatedAtEpochMs)
        assertEquals(PendingOp.NONE, only.pendingOp)
    }

    @Test
    fun mergePull_pendingRowWinsOverServer() {
        val local =
            listOf(
                row(
                    localId = "local-1",
                    serverId = 42,
                    title = "local edit",
                    pendingOp = PendingOp.UPDATE,
                    pendingFields = "title",
                ),
            )
        val server = listOf(serverTodo(42, "server title"))

        val merged = SyncCoordinator.mergePull(local, server)

        val only = merged.single()
        assertEquals("local edit", only.title)
        assertEquals(PendingOp.UPDATE, only.pendingOp)
        assertEquals("title", only.pendingFields)
    }

    @Test
    fun mergePull_pendingDeleteRowIsKeptForTheNextPush() {
        val local = listOf(row(localId = "d1", serverId = 42, pendingOp = PendingOp.DELETE))
        val server = listOf(serverTodo(42))

        val merged = SyncCoordinator.mergePull(local, server)

        val only = merged.single()
        assertEquals("d1", only.localId)
        assertEquals(PendingOp.DELETE, only.pendingOp)
    }

    @Test
    fun mergePull_rowGoneOnServerIsRemovedEvenWhenPendingDelete() {
        val local = listOf(row(localId = "d1", serverId = 42, pendingOp = PendingOp.DELETE))

        val merged = SyncCoordinator.mergePull(local, emptyList())

        assertTrue(merged.isEmpty())
    }

    @Test
    fun mergePull_serverDeletionRemovesLocalRow() {
        val local = listOf(row(localId = "a", serverId = 42), row(localId = "b", serverId = 43))
        val server = listOf(serverTodo(43))

        val merged = SyncCoordinator.mergePull(local, server)

        assertEquals(setOf(43L), merged.map { it.serverId }.toSet())
    }

    @Test
    fun mergePull_pendingCreateWithoutServerIdIsKept() {
        val local = listOf(row(localId = "c1", serverId = null, title = "offline", pendingOp = PendingOp.CREATE))
        val server = listOf(serverTodo(7, "Other"))

        val merged = SyncCoordinator.mergePull(local, server)

        assertEquals(2, merged.size)
        val created = merged.first { it.localId == "c1" }
        assertEquals(PendingOp.CREATE, created.pendingOp)
        assertNull(created.serverId)
        assertEquals(7L, merged.first { it.localId != "c1" }.serverId)
    }

    // ----------------------------------------------------------- push: create

    @Test
    fun push_createPostsFullStateAndClearsPending() {
        val dao = InMemoryTodoDao()
        dao.seed(
            row(
                localId = "c1",
                serverId = null,
                title = "Offline",
                notes = "n",
                dueDate = LocalDate.parse("2026-11-30"),
                pendingOp = PendingOp.CREATE,
                createdAt = 10,
            ),
        )
        val api = FakeApi()
        api.onCreate = { call ->
            assertEquals("Offline", call.title)
            assertEquals("n", call.notes)
            assertEquals(LocalDate.parse("2026-11-30"), call.dueDate)
            ack(id = 7, title = call.title, notes = call.notes, dueDate = "2026-11-30")
        }
        api.serverList = listOf(serverTodo(7, "Offline", notes = "n", dueDate = "2026-11-30", updatedAt = "2026-11-21T10:00:00Z"))

        val state = runBlocking { coordinator(dao, api).runNow() }

        val pushed = dao.rows["c1"]!!
        assertEquals(7L, pushed.serverId)
        assertEquals(PendingOp.NONE, pushed.pendingOp)
        assertEquals("", pushed.pendingFields)
        assertEquals(parseApiTimestamp("2026-11-21T10:00:00Z"), pushed.updatedAtEpochMs)
        assertTrue(api.patchCalls.isEmpty())
        assertEquals(SyncState.Idle, state)
        assertEquals(5_000L, syncedAt)
    }

    @Test
    fun push_createWithCompletionChainsIntoAPatchNextCycle() {
        // A POST can't carry `completed` (Appendix A) — the ack disagrees with
        // the local flag, which must survive as a pending UPDATE and land via
        // the coordinator's re-run of the cycle.
        val dao = InMemoryTodoDao()
        dao.seed(
            row(
                localId = "c1",
                serverId = null,
                title = "Done offline",
                pendingOp = PendingOp.CREATE,
                completed = true,
                completedAt = 2_000,
            ),
        )
        val api = FakeApi()
        api.onCreate = { ack(id = 7, title = it.title) } // server echoes completed=false
        api.onPatch = { call ->
            assertEquals(7L, call.id)
            assertEquals("""{"completed":true}""", call.body.toString())
            ack(id = 7, title = "Done offline", completed = true)
        }
        api.serverList = listOf(serverTodo(7, "Done offline", completed = true, completedAt = "2026-11-21T10:00:00Z"))

        val state = runBlocking { coordinator(dao, api).runNow() }

        val pushed = dao.rows["c1"]!!
        assertEquals(PendingOp.NONE, pushed.pendingOp)
        assertTrue(pushed.completed)
        assertEquals(parseApiTimestamp("2026-11-21T10:00:00Z"), pushed.completedAtEpochMs)
        assertEquals(1, api.createCalls.size)
        assertEquals(1, api.patchCalls.size)
        assertEquals(SyncState.Idle, state)
    }

    // ------------------------------------------------------------ push: patch

    @Test
    fun push_patchSendsOnlyPendingFieldsAndClears() {
        val dao = InMemoryTodoDao()
        dao.seed(
            row(
                localId = "u1",
                serverId = 42,
                title = "Edited",
                notes = "kept",
                dueDate = LocalDate.parse("2026-11-25"),
                pendingOp = PendingOp.UPDATE,
                pendingFields = "title",
            ),
        )
        val api = FakeApi()
        api.onPatch = { call ->
            assertEquals(42L, call.id)
            assertEquals("""{"title":"Edited"}""", call.body.toString())
            ack(42, title = "Edited", notes = "kept", dueDate = "2026-11-25")
        }
        api.serverList = listOf(serverTodo(42, "Edited", notes = "kept", dueDate = "2026-11-25"))

        val state = runBlocking { coordinator(dao, api).runNow() }

        val pushed = dao.rows["u1"]!!
        assertEquals(PendingOp.NONE, pushed.pendingOp)
        assertEquals("", pushed.pendingFields)
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun push_patch404DeletesRowLocallyAndCycleContinues() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "u1", serverId = 42, title = "Gone", pendingOp = PendingOp.UPDATE, pendingFields = "title"))
        val api = FakeApi()
        api.onPatch = { throw ApiException.HttpError(404, "no todo") }
        api.serverList = listOf(serverTodo(43, "Survivor"))

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertNull(dao.rows["u1"]) // hard-deleted server-side → deletion wins
        assertTrue(dao.rows.values.any { it.serverId == 43L })
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun push_pushesQueueOldestFirstAcrossOps() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "b-create", serverId = null, pendingOp = PendingOp.CREATE, createdAt = 20))
        dao.seed(row(localId = "a-update", serverId = 42, pendingOp = PendingOp.UPDATE, pendingFields = "title", createdAt = 10))
        val order = mutableListOf<String>()
        val api = FakeApi()
        api.onPatch = { order += "patch"; ack(42) }
        api.onCreate = { order += "create"; ack(id = 9, title = it.title) }
        api.serverList = listOf(serverTodo(42, "Local todo"), serverTodo(9, "Local todo"))

        runBlocking { coordinator(dao, api).runNow() }

        // The UPDATE (createdAt=10) pushes before the CREATE (createdAt=20).
        assertEquals(listOf("patch", "create"), order)
        assertEquals(PendingOp.NONE, dao.rows["a-update"]!!.pendingOp)
        assertEquals(PendingOp.NONE, dao.rows["b-create"]!!.pendingOp)
    }

    // ----------------------------------------------------------- push: delete

    @Test
    fun push_deleteRemovesRowOn204() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "d1", serverId = 42, pendingOp = PendingOp.DELETE))
        val api = FakeApi()
        api.onDelete = { assertEquals(42L, it) }

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertTrue(dao.rows.isEmpty())
        assertEquals(SyncState.Idle, state)
    }

    @Test
    fun push_deleteTreats404AsGone() {
        // The API contract swallows 404 before the engine sees it; the row must
        // still be removed locally either way.
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "d1", serverId = 42, pendingOp = PendingOp.DELETE))
        val api = FakeApi()
        api.onDelete = { /* 404 swallowed by TareasApi — fake models the contract */ }

        runBlocking { coordinator(dao, api).runNow() }

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun push_deleteServerErrorStopsCycleWithQueueIntact() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "d1", serverId = 42, pendingOp = PendingOp.DELETE))
        val api = FakeApi()
        api.onDelete = { throw ApiException.HttpError(500, "boom") }

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertTrue(state is SyncState.Error)
        assertEquals(PendingOp.DELETE, dao.rows["d1"]!!.pendingOp)
        assertEquals(0, api.listCalls.size) // stopped before the pull
        assertNull(syncedAt)
    }

    // ------------------------------------------------------- stop conditions

    @Test
    fun push_networkErrorStopsCycleAndKeepsQueue() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "c1", serverId = null, pendingOp = PendingOp.CREATE))
        val api = FakeApi()
        api.onCreate = { throw IOException("airplane mode") }

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertTrue(state is SyncState.Error)
        assertEquals(PendingOp.CREATE, dao.rows["c1"]!!.pendingOp)
        assertEquals(1, api.createCalls.size)
        assertEquals(0, api.listCalls.size)
        assertNull(syncedAt)
    }

    @Test
    fun push_unauthorizedStopsWithNeedsAuthAndKeepsData() {
        val dao = InMemoryTodoDao()
        dao.seed(row(localId = "u1", serverId = 42, pendingOp = PendingOp.UPDATE, pendingFields = "title"))
        val api = FakeApi()
        api.onPatch = { throw ApiException.Unauthorized("revoked") }

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertEquals(SyncState.NeedsAuth, state)
        assertEquals(PendingOp.UPDATE, dao.rows["u1"]!!.pendingOp)
        assertEquals(0, api.listCalls.size)
    }

    @Test
    fun pull_networkErrorSurfaces() {
        val dao = InMemoryTodoDao()
        dao.seed(row()) // nothing pending
        val api = FakeApi()
        api.onList = { throw IOException("timeout") }

        val state = runBlocking { coordinator(dao, api).runNow() }

        assertTrue(state is SyncState.Error)
        assertEquals(1, api.listCalls.size)
        assertNull(syncedAt)
    }

    @Test
    fun runNow_withoutCredentialsIsNeedsAuthWithoutAnyNetworkCall() {
        val dao = InMemoryTodoDao()
        val api = FakeApi()
        val coordinator = SyncCoordinator(dao, api, credentials = { null })

        val state = runBlocking { coordinator.runNow() }

        assertEquals(SyncState.NeedsAuth, state)
        assertEquals(0, api.listCalls.size)
    }

    // ------------------------------------------------------ ack reconciliation

    @Test
    fun reconcile_plainCreateClearsEverything() {
        val current =
            row(
                localId = "c1",
                serverId = null,
                title = "Offline",
                notes = "n",
                dueDate = LocalDate.parse("2026-11-30"),
                pendingOp = PendingOp.CREATE,
            )
        val ackTodo = ack(7, title = "Offline", notes = "n", dueDate = "2026-11-30")

        val reconciled = SyncCoordinator.reconcileWithAck(current, ackTodo)

        assertEquals(7L, reconciled.serverId)
        assertEquals(PendingOp.NONE, reconciled.pendingOp)
        assertEquals("", reconciled.pendingFields)
        assertEquals("c1", reconciled.localId)
    }

    @Test
    fun reconcile_midFlightEditSurvivesTheAck() {
        // Row was pushed with title "A"; the user typed "B" while the request
        // was in flight. The ack's values must not clobber "B".
        val current =
            row(
                localId = "u1",
                serverId = 42,
                title = "B",
                pendingOp = PendingOp.UPDATE,
                pendingFields = "title",
            )
        val ackTodo = ack(42, title = "A", notes = null, dueDate = null)

        val reconciled = SyncCoordinator.reconcileWithAck(current, ackTodo)

        assertEquals("B", reconciled.title)
        assertEquals(PendingOp.UPDATE, reconciled.pendingOp)
        assertEquals("title", reconciled.pendingFields)
    }

    @Test
    fun reconcile_deleteQueuedMidFlightWins() {
        val current = row(localId = "u1", serverId = 42, pendingOp = PendingOp.DELETE)
        val ackTodo = ack(42, title = "whatever")

        val reconciled = SyncCoordinator.reconcileWithAck(current, ackTodo)

        assertEquals(current, reconciled)
        assertEquals(PendingOp.DELETE, reconciled.pendingOp)
    }

    @Test
    fun reconcile_fieldsMatchingTheAckAreAdoptedNotRequeued() {
        // UPDATE{title} landed; notes/due match the server already → only
        // nothing new pending, and the ack's stamps are adopted.
        val current =
            row(
                localId = "u1",
                serverId = 42,
                title = "same",
                notes = "same notes",
                dueDate = LocalDate.parse("2026-11-25"),
                completed = true,
                completedAt = 111,
                pendingOp = PendingOp.UPDATE,
                pendingFields = "title",
            )
        val ackTodo = ack(42, title = "same", notes = "same notes", dueDate = "2026-11-25", completed = true)

        val reconciled = SyncCoordinator.reconcileWithAck(current, ackTodo)

        assertEquals(PendingOp.NONE, reconciled.pendingOp)
        assertEquals("", reconciled.pendingFields)
        assertEquals(parseApiTimestamp("2026-11-21T10:00:00Z"), reconciled.completedAtEpochMs)
    }

    @Test
    fun reconcile_completedFlagSurvivesEvenWhenTimestampsDiffer() {
        // Local stamp is the device clock; the server's differs. Only the FLAG
        // may re-queue — a timestamp-only diff would loop forever.
        val current =
            row(
                localId = "u1",
                serverId = 42,
                completed = true,
                completedAt = 999,
                pendingOp = PendingOp.UPDATE,
                pendingFields = "completed",
            )
        val ackTodo = ack(42, completed = true)

        val reconciled = SyncCoordinator.reconcileWithAck(current, ackTodo)

        assertEquals(PendingOp.NONE, reconciled.pendingOp)
        assertTrue(reconciled.completed)
        assertEquals(parseApiTimestamp("2026-11-21T10:00:00Z"), reconciled.completedAtEpochMs)
    }
}
