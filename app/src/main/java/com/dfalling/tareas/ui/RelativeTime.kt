package com.dfalling.tareas.ui

import com.dfalling.tareas.R
import com.dfalling.tareas.UiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The top bar's "Synced …" subtitle (PLAN.md §7): relative for a week —
 * just now / Xm / Xh / Xd — then the date itself (webapp dd-MM-yy convention).
 * Pure so the buckets are unit-tested; rendering is ticked every 30 s by the
 * caller.
 */
fun syncAgeText(
    lastSyncedAt: Long?,
    nowMs: Long,
    locale: Locale = Locale.getDefault(),
): UiText {
    if (lastSyncedAt == null) return UiText(R.string.sync_never)
    // A clock that's momentarily behind the stamp reads as "just now", never negative.
    val age = (nowMs - lastSyncedAt).coerceAtLeast(0)
    return when {
        age < 60_000L -> UiText(R.string.sync_just_now)
        age < 3_600_000L -> UiText(R.string.sync_minutes_ago, age / 60_000L)
        age < 86_400_000L -> UiText(R.string.sync_hours_ago, age / 3_600_000L)
        age < 7 * 86_400_000L -> UiText(R.string.sync_days_ago, age / 86_400_000L)
        else ->
            UiText(
                R.string.sync_on_date,
                DateTimeFormatter.ofPattern("dd-MM-yy", locale)
                    .format(Instant.ofEpochMilli(lastSyncedAt).atZone(ZoneId.systemDefault())),
            )
    }
}
