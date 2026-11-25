package com.dfalling.tareas.domain

import com.dfalling.tareas.data.TodoEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The four tabs, named after the webapp's ?view= values (internal/todos/tabs.go). */
enum class Tab(val view: String) {
    TODAY("today"),
    UPCOMING("upcoming"),
    UNSCHEDULED("unscheduled"),
    COMPLETED("completed"),
    ;

    companion object {
        /** Anything unrecognized, including null, is the default Today tab (ParseView). */
        fun fromView(raw: String?): Tab = entries.firstOrNull { it.view == raw } ?: TODAY
    }
}

/** One Upcoming section: a calendar day and its todos. */
data class DayGroup(
    val date: LocalDate,
    val todos: List<TodoEntity>,
)

/** The four tab sections, each sorted and ready to render. */
data class Tabs(
    val today: List<TodoEntity>,
    val upcoming: List<DayGroup>,
    val unscheduled: List<TodoEntity>,
    val completed: List<TodoEntity>,
) {
    companion object {
        /**
         * Port of tareas' SliceIntoTabs (internal/todos/tabs.go). Pure: input
         * order doesn't matter, every section is sorted deterministically —
         * alphabetical (lower(title), title, id) everywhere except Completed,
         * which is most-recently-completed first.
         */
        fun partition(todos: List<TodoEntity>, today: LocalDate): Tabs {
            val todayList = mutableListOf<TodoEntity>()
            val unscheduled = mutableListOf<TodoEntity>()
            val completed = mutableListOf<TodoEntity>()
            val byDay = sortedMapOf<LocalDate, MutableList<TodoEntity>>()

            for (todo in todos) {
                val dueDay = todo.dueDateEpochDay
                when {
                    // Completed always wins over everything.
                    todo.completed -> completed += todo
                    dueDay == null -> unscheduled += todo
                    // Later than today → Upcoming; due today or overdue → Today.
                    dueDay > today.toEpochDay() ->
                        byDay.getOrPut(LocalDate.ofEpochDay(dueDay)) { mutableListOf() }.add(todo)
                    else -> todayList += todo
                }
            }

            return Tabs(
                today = todayList.sortedWith(byTitle),
                upcoming = byDay.map { (date, list) -> DayGroup(date, list.sortedWith(byTitle)) },
                unscheduled = unscheduled.sortedWith(byTitle),
                completed = completed.sortedWith(byCompletedDesc),
            )
        }

        /**
         * Unfinished with a due date before today; due today is NOT overdue yet
         * (mirrors the web UI's red label rule).
         */
        fun isOverdue(todo: TodoEntity, today: LocalDate): Boolean =
            !todo.completed && todo.dueDateEpochDay != null && todo.dueDateEpochDay < today.toEpochDay()

        /**
         * Upcoming group heading: "Tomorrow" for the next day, else the locale
         * weekday + "dd-MM-yy" ("Wed 25-11-26") — the webapp's dayLabel.
         */
        fun headerLabel(day: LocalDate, today: LocalDate, locale: Locale): String =
            if (day == today.plusDays(1)) {
                "Tomorrow"
            } else {
                DateTimeFormatter.ofPattern("EEE dd-MM-yy", locale).format(day)
            }

        /** lower(title), then title (case tie: "M" < "m"), then id — the Go cmp.Or twin. */
        private val byTitle =
            compareBy(
                { t: TodoEntity -> t.title.lowercase() },
                { t: TodoEntity -> t.title },
                { t: TodoEntity -> t.sortId },
            )

        /** Most recently completed first; no timestamp sorts last; id desc breaks exact ties. */
        private val byCompletedDesc =
            compareByDescending<TodoEntity> { it.completedAtEpochMs ?: Long.MIN_VALUE }
                .thenByDescending { it.sortId }
    }
}
