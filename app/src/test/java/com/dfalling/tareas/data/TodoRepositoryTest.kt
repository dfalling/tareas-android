package com.dfalling.tareas.data

import com.dfalling.tareas.testhelpers.InMemoryTodoDao
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline op-collapsing rules (PLAN.md §4) driven through the real
 * repository against an in-memory DAO, plus the "mutation fires the sync
 * trigger" contract.
 */
class TodoRepositoryTest {
    private val dao = InMemoryTodoDao()
    private var now = 1_000L
    private var mutations = 0
    private val repository =
        TodoRepository(
            dao = dao,
            clock = {
                ++now
                now
            },
            onMutated = { mutations += 1 },
        )

    /** Suspend-free seeding for test setup. */
    private fun seed(vararg rows: TodoEntity) {
        runBlocking { rows.forEach { dao.upsert(it) } }
    }

    private fun syncedRow(
        localId: String = "local-1",
        serverId: Long = 42L,
        title: String = "Original",
        notes: String? = null,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        pendingOp: PendingOp = PendingOp.NONE,
        pendingFields: String = "",
    ): TodoEntity =
        TodoEntity(
            localId = localId,
            serverId = serverId,
            title = title,
            notes = notes,
            dueDateEpochDay = dueDate?.toEpochDay(),
            completed = completed,
            completedAtEpochMs = if (completed) 500L else null,
            createdAtEpochMs = 100L,
            updatedAtEpochMs = 200L,
            pendingOp = pendingOp,
            pendingFields = pendingFields,
        )

    // ---------------------------------------------------------------- create

    @Test
    fun createWritesPendingCreateRowWithLocalStamps() {
        val created = runBlocking { repository.create("  Milk  ", "  ", LocalDate.parse("2026-11-30")) }

        val row = dao.rows.getValue(created.localId)
        assertEquals("Milk", row.title) // trimmed like the server would
        assertNull(row.notes) // whitespace-only → null (server rule mirrored)
        assertEquals(LocalDate.parse("2026-11-30"), row.dueDate)
        assertEquals(PendingOp.CREATE, row.pendingOp)
        assertEquals("", row.pendingFields)
        assertNull(row.serverId)
        assertEquals(1_001L, row.createdAtEpochMs)
        assertTrue(row.isPending)
        assertEquals(1, mutations)
    }

    // ------------------------------------------------------- create collapsing

    @Test
    fun createThenEdit_staysCreateWithNewValues() {
        runBlocking {
            val created = repository.create("Milk", null, null)
            repository.edit(created.localId, "Milk 2%", "2 liters", LocalDate.parse("2026-12-01"))
        }

        val row = dao.rows.values.single()
        assertEquals(PendingOp.CREATE, row.pendingOp) // POST will send full state
        assertEquals("", row.pendingFields)
        assertEquals("Milk 2%", row.title)
        assertEquals("2 liters", row.notes)
        assertEquals(LocalDate.parse("2026-12-01"), row.dueDate)
    }

    @Test
    fun createThenDelete_removesTheRowEntirely() {
        runBlocking {
            val created = repository.create("Milk", null, null)
            repository.delete(created.localId)
        }

        assertTrue(dao.rows.isEmpty())
        assertEquals(2, mutations)
    }

    // --------------------------------------------------------- edit collapsing

    @Test
    fun editOnSyncedRow_becomesUpdateWithChangedFieldsOnly() {
        seed(syncedRow(title = "Original", notes = "old", dueDate = LocalDate.parse("2026-11-25")))

        runBlocking { repository.edit("local-1", title = "Renamed", notes = "old", dueDate = LocalDate.parse("2026-11-25")) }

        val row = dao.rows.getValue("local-1")
        assertEquals(PendingOp.UPDATE, row.pendingOp)
        assertEquals("title", row.pendingFields) // notes/due unchanged → not re-sent
        assertEquals("Renamed", row.title)
    }

    @Test
    fun editAccumulatesPendingFieldsAcrossEdits() {
        seed(syncedRow(title = "Original", pendingOp = PendingOp.UPDATE, pendingFields = "title"))

        runBlocking { repository.edit("local-1", title = "Original", notes = "new notes", dueDate = null) }

        val row = dao.rows.getValue("local-1")
        assertEquals(PendingOp.UPDATE, row.pendingOp)
        assertEquals("notes", row.pendingFieldSet.single { it != "title" })
        assertEquals(setOf("title", "notes"), row.pendingFieldSet)
    }

    @Test
    fun editWithNoActualChange_isANoOpWithoutTrigger() {
        seed(syncedRow(title = "Same", notes = "n"))
        val before = dao.rows.getValue("local-1")
        val mutationsBefore = mutations

        runBlocking { repository.edit("local-1", title = "Same", notes = "n", dueDate = null) }

        assertEquals(before, dao.rows.getValue("local-1"))
        assertEquals(mutationsBefore, mutations)
    }

    @Test
    fun editOnPendingDeleteRow_isIgnored() {
        seed(syncedRow(pendingOp = PendingOp.DELETE))

        runBlocking { repository.edit("local-1", title = "resurrect?", notes = null, dueDate = null) }

        val row = dao.rows.getValue("local-1")
        assertEquals(PendingOp.DELETE, row.pendingOp)
        assertEquals("Original", row.title) // untouched
    }

    // ---------------------------------------------------- completion collapsing

    @Test
    fun completeOnSyncedRow_stampsAndQueuesCompletedField() {
        seed(syncedRow())

        runBlocking { repository.setCompleted("local-1", completed = true) }

        val row = dao.rows.getValue("local-1")
        assertTrue(row.completed)
        assertEquals(1_001L, row.completedAtEpochMs) // local clock; ack refreshes it later
        assertEquals(PendingOp.UPDATE, row.pendingOp)
        assertEquals("completed", row.pendingFields)
    }

    @Test
    fun reopenClearsTheCompletionStamp() {
        seed(syncedRow(completed = true))

        runBlocking { repository.setCompleted("local-1", completed = false) }

        val row = dao.rows.getValue("local-1")
        assertFalse(row.completed)
        assertNull(row.completedAtEpochMs)
        assertEquals("completed", row.pendingFields)
    }

    @Test
    fun completeOnPendingCreate_staysCreate() {
        runBlocking {
            val created = repository.create("Milk", null, null)
            repository.setCompleted(created.localId, completed = true)
        }

        val row = dao.rows.values.single()
        assertEquals(PendingOp.CREATE, row.pendingOp) // POST still carries the full state
        assertTrue(row.completed)
        assertEquals(1_002L, row.completedAtEpochMs) // create ticked 1001, completion ticked 1002
    }

    @Test
    fun completingWithNoChange_isANoOpWithoutTrigger() {
        seed(syncedRow(completed = true))
        val before = dao.rows.getValue("local-1")
        val mutationsBefore = mutations

        runBlocking { repository.setCompleted("local-1", completed = true) }

        assertEquals(before, dao.rows.getValue("local-1"))
        assertEquals(mutationsBefore, mutations)
    }

    // -------------------------------------------------------- delete collapsing

    @Test
    fun deleteOnSyncedRow_queuesPendingDelete() {
        seed(syncedRow())

        runBlocking { repository.delete("local-1") }

        val row = dao.rows.getValue("local-1")
        assertEquals(PendingOp.DELETE, row.pendingOp)
        assertEquals("", row.pendingFields)
        assertEquals(42L, row.serverId) // stays until the DELETE lands
    }

    @Test
    fun deleteOnPendingUpdateRow_upgradesThePendingOp() {
        seed(syncedRow(pendingOp = PendingOp.UPDATE, pendingFields = "title,notes"))

        runBlocking { repository.delete("local-1") }

        val row = dao.rows.getValue("local-1")
        assertEquals(PendingOp.DELETE, row.pendingOp)
        assertEquals("", row.pendingFields) // the PATCH will never happen
    }

    @Test
    fun deleteTwice_isANoOpWithoutSecondTrigger() {
        seed(syncedRow())
        runBlocking { repository.delete("local-1") }
        val afterFirst = dao.rows.getValue("local-1")
        assertEquals(1, mutations)

        runBlocking { repository.delete("local-1") }

        assertEquals(afterFirst, dao.rows.getValue("local-1"))
        assertEquals(1, mutations)
    }

    @Test
    fun deleteOfUnknownRow_isSilentlyIgnored() {
        runBlocking { repository.delete("nope") }
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, mutations)
    }
}
