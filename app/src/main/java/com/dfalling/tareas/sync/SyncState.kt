package com.dfalling.tareas.sync

import com.dfalling.tareas.UiText

/** Sync status the UI observes (PLAN.md §5). */
sealed interface SyncState {
    /** Last cycle finished clean. */
    data object Idle : SyncState

    /** A cycle is running right now. */
    data object Syncing : SyncState

    /** Cycle stopped on a failure other than auth; pending ops stay queued for retry. */
    data class Error(val reason: UiText) : SyncState

    /** 401 mid-sync (or no credentials): Room data kept, login screen comes back. */
    data object NeedsAuth : SyncState
}
