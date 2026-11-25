package com.dfalling.tareas.domain

import com.dfalling.tareas.data.PendingOp
import com.dfalling.tareas.data.TodoEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Ports of the fixtures in tareas/internal/todos/tabs_test.go. */
class TabsTest {
    /** Fixed clock: 2026-11-24, the webapp test's "today". */
    private val today: LocalDate = LocalDate.parse("2026-11-24")

    private fun todo(
        id: Long,
        title: String,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        completedAt: Long? = null,
    ): TodoEntity =
        TodoEntity(
            localId = "local-$id",
            serverId = id,
            title = title,
            notes = null,
            dueDateEpochDay = dueDate?.toEpochDay(),
            completed = completed,
            completedAtEpochMs = completedAt,
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            pendingOp = PendingOp.NONE,
            pendingFields = "",
        )

    private fun titles(todos: List<TodoEntity>): List<String> = todos.map { it.title }

    private fun epochMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun partitionsIntoTabs() {
        val completedEarly = epochMs("2026-11-20T09:00:00Z")
        val completedLate = epochMs("2026-11-23T18:00:00Z")

        val tabs =
            Tabs.partition(
                listOf(
                    todo(1, "due today", dueDate = LocalDate.parse("2026-11-24")),
                    todo(2, "overdue", dueDate = LocalDate.parse("2026-11-18")),
                    todo(3, "tomorrow", dueDate = LocalDate.parse("2026-11-25")),
                    todo(4, "next week", dueDate = LocalDate.parse("2026-11-30")),
                    todo(5, "unscheduled"),
                    todo(6, "done first", completed = true, completedAt = completedEarly),
                    todo(7, "done last", completed = true, completedAt = completedLate),
                ),
                today,
            )

        assertEquals(listOf("due today", "overdue"), titles(tabs.today))
        assertEquals(2, tabs.upcoming.size)
        assertEquals(LocalDate.parse("2026-11-25"), tabs.upcoming[0].date)
        assertEquals(listOf("tomorrow"), titles(tabs.upcoming[0].todos))
        assertEquals(LocalDate.parse("2026-11-30"), tabs.upcoming[1].date)
        assertEquals(listOf("unscheduled"), titles(tabs.unscheduled))
        // Most recently completed first.
        assertEquals(listOf("done last", "done first"), titles(tabs.completed))
    }

    @Test
    fun emptyInputGivesEmptyTabs() {
        val tabs = Tabs.partition(emptyList(), today)
        assertEquals(Tabs(emptyList(), emptyList(), emptyList(), emptyList()), tabs)
    }

    @Test
    fun alphabeticalWithCaseTiebreak() {
        // lower(title) decides; the raw title breaks ties between titles that
        // differ only in case ("M" sorts before "m"), and the id is the final
        // tiebreak — mirroring ORDER BY lower(title), title, id.
        val tabs =
            Tabs.partition(
                listOf(
                    todo(2, "banana"),
                    todo(3, "Apple pie"),
                    todo(4, "milk", dueDate = LocalDate.parse("2026-11-24")),
                    todo(5, "Milk", dueDate = LocalDate.parse("2026-11-24")),
                    todo(1, "apple"),
                ),
                today,
            )

        assertEquals(listOf("Milk", "milk"), titles(tabs.today))
        assertEquals(listOf("apple", "Apple pie", "banana"), titles(tabs.unscheduled))
    }

    @Test
    fun unsyncedCreatesSortLastOnExactTie() {
        val unsynced =
            todo(1, "Milk", dueDate = today).copy(localId = "local-new", serverId = null)
        val tabs = Tabs.partition(listOf(todo(2, "Milk", dueDate = today), unsynced), today)
        assertEquals(listOf("Milk", "Milk"), titles(tabs.today))
        assertEquals("local-2", tabs.today[0].localId)
        assertEquals("local-new", tabs.today[1].localId)
    }

    @Test
    fun upcomingGroupsByDayAscending() {
        // Days ascend and repeat days share one group, alphabetical within it.
        val tabs =
            Tabs.partition(
                listOf(
                    todo(1, "late", dueDate = LocalDate.parse("2026-11-27")),
                    todo(2, "second same day", dueDate = LocalDate.parse("2026-11-25")),
                    todo(3, "first same day", dueDate = LocalDate.parse("2026-11-25")),
                    todo(4, "middle", dueDate = LocalDate.parse("2026-11-26")),
                ),
                today,
            )

        assertEquals(3, tabs.upcoming.size)
        assertEquals(
            listOf("2026-11-25", "2026-11-26", "2026-11-27"),
            tabs.upcoming.map { it.date.toString() },
        )
        assertEquals(
            listOf("first same day", "second same day"),
            titles(tabs.upcoming[0].todos),
        )
    }

    @Test
    fun overdueTable() {
        val cases: List<Pair<String, Pair<TodoEntity, Boolean>>> =
            listOf(
                "due yesterday" to
                    Pair(
                        todo(1, "x", dueDate = LocalDate.parse("2026-11-23")),
                        true,
                    ),
                "due today" to
                    Pair(
                        todo(1, "x", dueDate = LocalDate.parse("2026-11-24")),
                        false,
                    ),
                "due tomorrow" to
                    Pair(
                        todo(1, "x", dueDate = LocalDate.parse("2026-11-25")),
                        false,
                    ),
                "unscheduled" to Pair(todo(1, "x"), false),
                "completed but overdue" to
                    Pair(
                        todo(1, "x", dueDate = LocalDate.parse("2026-11-01"), completed = true),
                        false,
                    ),
            )
        for ((name, pair) in cases) {
            val (t, want) = pair
            assertEquals(name, want, Tabs.isOverdue(t, today))
        }
    }

    @Test
    fun headerLabels() {
        assertEquals("Tomorrow", Tabs.headerLabel(LocalDate.parse("2026-11-25"), today, Locale.US))
        assertEquals("Thu 26-11-26", Tabs.headerLabel(LocalDate.parse("2026-11-26"), today, Locale.US))
        assertEquals(
            "jeu. 26-11-26",
            Tabs.headerLabel(LocalDate.parse("2026-11-26"), today, Locale.FRENCH),
        )
    }

    @Test
    fun fromViewMirrorsParseView() {
        assertEquals(Tab.TODAY, Tab.fromView(null))
        assertEquals(Tab.TODAY, Tab.fromView(""))
        assertEquals(Tab.TODAY, Tab.fromView("bogus"))
        assertEquals(Tab.TODAY, Tab.fromView("Today"))
        assertEquals(Tab.UPCOMING, Tab.fromView("upcoming"))
        assertEquals(Tab.UNSCHEDULED, Tab.fromView("unscheduled"))
        assertEquals(Tab.COMPLETED, Tab.fromView("completed"))
    }
}
