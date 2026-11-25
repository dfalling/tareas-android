package com.dfalling.tareas.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PATCH body contract: only pendingFields go out; absent field = untouched,
 * explicit null = clear (server rejects unknown fields, so nothing extra may leak).
 */
class PatchBodyTest {
    private fun entity(
        title: String = "Title",
        notes: String? = "notes",
        dueDate: LocalDate? = LocalDate.parse("2026-11-25"),
        completed: Boolean = false,
        pendingFields: String = "",
    ): TodoEntity =
        TodoEntity(
            localId = "local-1",
            serverId = 42L,
            title = title,
            notes = notes,
            dueDateEpochDay = dueDate?.toEpochDay(),
            completed = completed,
            completedAtEpochMs = null,
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            pendingOp = PendingOp.UPDATE,
            pendingFields = pendingFields,
        )

    @Test
    fun sendsOnlyTheRequestedFields() {
        val body = buildPatchBody(entity(), fields = listOf(PatchField.TITLE))
        assertEquals("""{"title":"Title"}""", body.toString())
    }

    @Test
    fun defaultsToTheStoredPendingFields() {
        val body = buildPatchBody(entity().copy(pendingFields = "title,completed"))
        assertEquals("""{"title":"Title","completed":false}""", body.toString())
    }

    @Test
    fun nullNotesClears() {
        val body =
            buildPatchBody(entity(notes = null), fields = listOf(PatchField.NOTES))
        assertEquals("""{"notes":null}""", body.toString())
    }

    @Test
    fun presentNotesIsTheString() {
        val body =
            buildPatchBody(entity(notes = "  spaced  "), fields = listOf(PatchField.NOTES))
        assertEquals("""{"notes":"  spaced  "}""", body.toString())
    }

    @Test
    fun nullDueDateClears() {
        val body =
            buildPatchBody(entity(dueDate = null), fields = listOf(PatchField.DUE_DATE))
        assertEquals("""{"due_date":null}""", body.toString())
    }

    @Test
    fun dueDateIsIsoDate() {
        val body =
            buildPatchBody(
                entity(dueDate = LocalDate.parse("2026-12-01")),
                fields = listOf(PatchField.DUE_DATE),
            )
        assertEquals("""{"due_date":"2026-12-01"}""", body.toString())
    }

    @Test
    fun completedFlipGoesOut() {
        val body =
            buildPatchBody(entity(completed = true), fields = listOf(PatchField.COMPLETED))
        assertEquals("""{"completed":true}""", body.toString())
    }

    @Test
    fun fieldOrderIsCanonicalRegardlessOfStoredOrder() {
        val body =
            buildPatchBody(
                entity().copy(pendingFields = "completed,title"),
                fields = entity().copy(pendingFields = "completed,title").pendingFieldSet,
            )
        assertEquals("""{"title":"Title","completed":false}""", body.toString())
    }

    @Test
    fun pendingFieldSetParsesCommaList() {
        assertEquals(
            setOf("title", "due_date"),
            entity(pendingFields = "title,due_date").pendingFieldSet,
        )
        assertEquals(emptySet<String>(), entity(pendingFields = "").pendingFieldSet)
    }
}
