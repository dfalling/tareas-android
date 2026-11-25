package com.dfalling.tareas.sync

import com.dfalling.tareas.data.ApiException
import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.TareasApi
import com.dfalling.tareas.data.TodoRepository
import com.dfalling.tareas.data.parseApiTimestamp
import com.dfalling.tareas.testhelpers.InMemoryTodoDao
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 3 acceptance smoke test (PLAN.md §7): the real [TareasApi] client and
 * the real [SyncCoordinator]/[TodoRepository] engine against a **live** tareas
 * server. Skips itself when no server is reachable, so CI and offline runs are
 * unaffected.
 *
 * Local run with the server up (PLAN.md Appendix E):
 * ```
 * TAREAS_TEST_URL=http://localhost:8081 ./gradlew testDebugUnitTest --tests '*SyncIntegrationTest*'
 * ```
 * The account must already exist (created once via the web /setup form); the
 * tests only touch todos they created themselves (timestamp-suffixed titles)
 * and delete them afterwards.
 */
class SyncIntegrationTest {
    private val baseUrl = System.getenv("TAREAS_TEST_URL") ?: "http://localhost:8081"
    private val email = System.getenv("TAREAS_TEST_EMAIL") ?: "smoke@test.dev"
    private val password = System.getenv("TAREAS_TEST_PASSWORD") ?: "smoke-password-123"

    private val api = TareasApi()
    private var cachedToken: String? = null

    /** Logs in once; skips the whole class when no server is there to log into. */
    private suspend fun token(): String {
        cachedToken?.let { return it }
        val fresh =
            try {
                api.login(baseUrl, email, password)
            } catch (e: IOException) {
                assumeNoException(e)
                unreachable()
            }
        assumeTrue(fresh.isNotBlank())
        cachedToken = fresh
        return fresh
    }

    /** assumeNoException always throws; this only satisfies the compiler. */
    private fun unreachable(): Nothing = throw AssertionError("assumption must have thrown")

    // ------------------------------------------------------------ API client

    @Test
    fun apiClient_fullRoundTripMatchesAppendixA() = runBlocking {
        val tok = token()

        val created =
            api.createTodo(baseUrl, tok, title = "Smoke api round-trip", notes = "before", dueDate = LocalDate.parse("2026-11-30"))
        try {
            assertTrue(created.id > 0)
            assertEquals("Smoke api round-trip", created.title)
            assertEquals("before", created.notes)
            assertEquals("2026-11-30", created.dueDate)
            assertFalse(created.completed)
            assertNull(created.completedAt)

            // completed flip stamps server-side
            val done = api.patchTodo(baseUrl, tok, created.id, buildJsonObject { put("completed", true) })
            assertTrue(done.completed)
            assertNotNull(done.completedAt)
            assertTrue(parseApiTimestamp(done.completedAt!!) > 0)

            // explicit null clears
            val cleared = api.patchTodo(baseUrl, tok, created.id, buildJsonObject { put("notes", null) })
            assertNull(cleared.notes)

            // unknown JSON fields are rejected on requests
            val reject = runCatching { api.patchTodo(baseUrl, tok, created.id, buildJsonObject { put("bogus_field", 1) }) }
            assertTrue(reject.exceptionOrNull() is ApiException.HttpError)

            // unknown id → 404 (the engine's hard-deleted-elsewhere path)
            val missing = runCatching { api.patchTodo(baseUrl, tok, created.id + 4_000_000, buildJsonObject { put("completed", false) }) }
            assertTrue((missing.exceptionOrNull() as? ApiException.HttpError)?.isNotFound == true)

            // delete is idempotent through the client: 204 then the swallowed 404
            api.deleteTodo(baseUrl, tok, created.id)
            api.deleteTodo(baseUrl, tok, created.id)
            assertTrue(api.listTodos(baseUrl, tok).none { it.id == created.id })
        } finally {
            runCatching { api.deleteTodo(baseUrl, tok, created.id) }
        }
    }

    @Test
    fun apiClient_badTokenIsUnauthorized() = runBlocking {
        token() // skip when no server is reachable
        val error = runCatching { api.listTodos(baseUrl, "definitely-not-a-token") }.exceptionOrNull()
        assertTrue(error is ApiException.Unauthorized)
    }

    // ------------------------------------------------- engine against server

    @Test
    fun engine_offlineCreateCompleteEditDelete_allReachTheServer() = runBlocking {
        val tok = token()
        val stamp = System.currentTimeMillis()
        val title = "Smoke engine $stamp"
        val dao = InMemoryTodoDao()
        val repository = TodoRepository(dao = dao, onMutated = {}) // no trigger loop in tests
        var lastSyncedAt = 0L
        val coordinator =
            SyncCoordinator(
                dao = dao,
                api = api,
                credentials = { baseUrl to tok },
                onSynced = { lastSyncedAt = it },
                clock = { System.currentTimeMillis() },
            )

        // "Offline": create + complete + rename locally — nothing touches the network.
        val created = repository.create(title, notes = "offline notes", dueDate = LocalDate.parse("2026-11-30"))
        repository.setCompleted(created.localId, completed = true)
        repository.edit(created.localId, title = "$title (renamed)", notes = "offline notes", dueDate = LocalDate.parse("2026-11-30"))
        assertNull(created.serverId)
        assertTrue(dao.rows.getValue(created.localId).isPending)
        assertTrue(api.listTodos(baseUrl, tok).none { it.title.startsWith(title) })

        // Reconnect: engine cycles must push the whole collapsed op queue.
        coordinator.runNow()

        val synced = dao.rows.getValue(created.localId)
        assertNotNull(synced.serverId)
        assertEquals(PendingOp.NONE, synced.pendingOp)
        assertTrue(synced.completed)
        val remote = api.listTodos(baseUrl, tok).first { it.title == "$title (renamed)" }
        assertEquals(synced.serverId, remote.id) // the POST ack's id stuck
        assertTrue(remote.completed)
        assertNotNull(remote.completedAt)
        assertEquals(parseApiTimestamp(remote.completedAt!!), synced.completedAtEpochMs)
        assertNotEquals(0L, lastSyncedAt)

        // And a queued delete leaves no trace after a cycle: the row is gone
        // locally and the server never heard of it again. (The pull itself
        // re-imports whatever else the shared server holds — that's by design.)
        repository.delete(created.localId)
        coordinator.runNow()
        assertFalse(dao.rows.containsKey(created.localId))
        assertTrue(api.listTodos(baseUrl, tok).none { it.title.startsWith(title) })
    }
}
