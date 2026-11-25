package com.dfalling.tareas.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DTO behavior against real JSON shapes from PLAN.md Appendix A. */
class TodoJsonTest {
    @Test
    fun decodesAppendixASample() {
        val text =
            """
            {
              "id": 42,
              "title": "Buy milk",
              "notes": "2 liters",
              "due_date": "2026-11-25",
              "completed": false,
              "completed_at": null,
              "created_at": "2026-11-20T09:00:00Z",
              "updated_at": "2026-11-20T09:00:00Z"
            }
            """
                .trimIndent()

        val todo = ApiJson.decodeFromString<TodoJson>(text)

        assertEquals(42L, todo.id)
        assertEquals("Buy milk", todo.title)
        assertEquals("2 liters", todo.notes)
        assertEquals("2026-11-25", todo.dueDate)
        assertEquals(false, todo.completed)
        assertNull(todo.completedAt)
        assertEquals("2026-11-20T09:00:00Z", todo.createdAt)
        assertEquals("2026-11-20T09:00:00Z", todo.updatedAt)
    }

    @Test
    fun decodesCompletedTodo() {
        val text =
            """
            {
              "id": 7,
              "title": "Done thing",
              "notes": null,
              "due_date": null,
              "completed": true,
              "completed_at": "2026-11-23T18:00:00Z",
              "created_at": "2026-11-20T09:00:00Z",
              "updated_at": "2026-11-23T18:00:00Z"
            }
            """
                .trimIndent()

        val todo = ApiJson.decodeFromString<TodoJson>(text)

        assertEquals(true, todo.completed)
        assertEquals("2026-11-23T18:00:00Z", todo.completedAt)
        assertNull(todo.notes)
        assertNull(todo.dueDate)
    }

    @Test
    fun toleratesUnknownFieldsAndMissingNullables() {
        val text =
            """
            {
              "id": 1,
              "title": "T",
              "created_at": "2026-11-20T09:00:00Z",
              "updated_at": "2026-11-20T09:00:00Z",
              "user_id": 1,
              "future_field": {"x": 1}
            }
            """
                .trimIndent()

        val todo = ApiJson.decodeFromString<TodoJson>(text)

        assertNull(todo.notes)
        assertNull(todo.dueDate)
        assertNull(todo.completedAt)
        assertEquals(false, todo.completed)
    }

    @Test
    fun parsesTimestampsTolerantly() {
        // Epoch anchors, offset equivalence — RFC3339 with Z or numeric offsets.
        assertEquals(0L, parseApiTimestamp("1970-01-01T00:00:00Z"))
        assertEquals(946684800000L, parseApiTimestamp("2000-01-01T00:00:00Z"))
        assertEquals(
            parseApiTimestamp("2026-11-20T09:00:00Z"),
            parseApiTimestamp("2026-11-20T11:00:00+02:00"),
        )
        assertEquals(
            parseApiTimestamp("2026-11-20T09:00:00Z"),
            parseApiTimestamp("2026-11-20T04:00:00-05:00"),
        )
    }

    @Test
    fun parsesDueDateAsEpochDay() {
        assertEquals(LocalDate.parse("2026-11-25").toEpochDay(), parseApiDate("2026-11-25"))
    }

    @Test
    fun mapsToFreshEntity() {
        val json =
            ApiJson.decodeFromString<TodoJson>(
                """
                {
                  "id": 42,
                  "title": "Buy milk",
                  "notes": "2 liters",
                  "due_date": "2026-11-25",
                  "completed": true,
                  "completed_at": "2026-11-23T18:00:00Z",
                  "created_at": "2026-11-20T09:00:00Z",
                  "updated_at": "2026-11-24T08:30:00+02:00"
                }
                """
                    .trimIndent(),
            )

        val entity = json.toEntity(localId = "abc")

        assertEquals("abc", entity.localId)
        assertEquals(42L, entity.serverId)
        assertEquals("Buy milk", entity.title)
        assertEquals("2 liters", entity.notes)
        assertEquals(LocalDate.parse("2026-11-25").toEpochDay(), entity.dueDateEpochDay)
        assertEquals(LocalDate.parse("2026-11-25"), entity.dueDate)
        assertEquals(true, entity.completed)
        assertEquals(parseApiTimestamp("2026-11-23T18:00:00Z"), entity.completedAtEpochMs)
        assertEquals(parseApiTimestamp("2026-11-20T09:00:00Z"), entity.createdAtEpochMs)
        assertEquals(parseApiTimestamp("2026-11-24T08:30:00+02:00"), entity.updatedAtEpochMs)
        assertEquals(PendingOp.NONE, entity.pendingOp)
        assertEquals("", entity.pendingFields)
        assertEquals(false, entity.isPending)
    }

    @Test
    fun overwriteKeepsIdentityAndPendingState() {
        val original =
            TodoEntity(
                localId = "abc",
                serverId = 42L,
                title = "Old",
                notes = null,
                dueDateEpochDay = null,
                completed = false,
                completedAtEpochMs = null,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                pendingOp = PendingOp.UPDATE,
                pendingFields = "title",
            )
        val json =
            ApiJson.decodeFromString<TodoJson>(
                """
                {
                  "id": 42,
                  "title": "New",
                  "notes": null,
                  "due_date": "2026-11-25",
                  "completed": false,
                  "completed_at": null,
                  "created_at": "2026-11-20T09:00:00Z",
                  "updated_at": "2026-11-24T08:00:00Z"
                }
                """
                    .trimIndent(),
            )

        val merged = json.overwrite(original)

        assertEquals("abc", merged.localId)
        assertEquals(42L, merged.serverId)
        assertEquals("New", merged.title)
        assertNull(merged.notes)
        assertEquals(LocalDate.parse("2026-11-25").toEpochDay(), merged.dueDateEpochDay)
        assertEquals(PendingOp.UPDATE, merged.pendingOp)
        assertEquals("title", merged.pendingFields)
    }

    @Test
    fun loginRequestEncodesExactly() {
        assertEquals(
            """{"email":"a@b.c","password":"s3cret"}""",
            ApiJson.encodeToString(LoginRequest(email = "a@b.c", password = "s3cret")),
        )
    }

    @Test
    fun loginResponseDecodes() {
        assertEquals("tok", ApiJson.decodeFromString<LoginResponse>("""{"token":"tok"}""").token)
    }
}
