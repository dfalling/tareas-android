package com.dfalling.tareas.ui

import com.dfalling.tareas.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60L * MINUTE
private const val DAY = 24L * HOUR

/** The top bar subtitle's age buckets (RelativeTime.kt). */
class RelativeTimeTest {
    private val locale = Locale.US

    @Test
    fun `never synced`() {
        assertEquals(R.string.sync_never, syncAgeText(null, nowMs = 0L, locale).id)
    }

    @Test
    fun `buckets just now minutes hours days`() {
        val synced = 1_000_000L
        fun age(ageMs: Long) = syncAgeText(synced, synced + ageMs, locale).id

        assertEquals(R.string.sync_just_now, age(0L))
        assertEquals(R.string.sync_just_now, age(59_999L))
        assertEquals(R.string.sync_minutes_ago, age(MINUTE))
        assertEquals(R.string.sync_minutes_ago, age(59L * MINUTE))
        assertEquals(R.string.sync_hours_ago, age(HOUR))
        assertEquals(R.string.sync_hours_ago, age(23L * HOUR))
        assertEquals(R.string.sync_days_ago, age(DAY))
        assertEquals(R.string.sync_days_ago, age(6L * DAY))
    }

    @Test
    fun `a week and older falls back to the date`() {
        val text = syncAgeText(1_000_000L, 1_000_000L + 7 * DAY, locale)
        assertEquals(R.string.sync_on_date, text.id)
        // The stamp is formatted in dd-MM-yy; the arg carries the rendered date.
        val rendered = text.args.single() as String
        org.junit.Assert.assertTrue(rendered.matches(Regex("""\d{2}-\d{2}-\d{2}""")))
    }

    @Test
    fun `clock behind the stamp never goes negative`() {
        assertEquals(R.string.sync_just_now, syncAgeText(1_000_000L, 999_000L, locale).id)
    }

    @Test
    fun `minute counts are whole units`() {
        val text = syncAgeText(0L, 5L * MINUTE + 59_000L, locale)
        assertEquals(5L, text.args.single())
    }
}
