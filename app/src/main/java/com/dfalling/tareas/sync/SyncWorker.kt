package com.dfalling.tareas.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dfalling.tareas.TareasApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Background safety net (PLAN.md §5): unique one-shot work that waits for
 * connectivity and retries with exponential backoff while the network keeps
 * failing, so mutations made offline land on their own once the device is
 * back online. All logic lives in [SyncCoordinator].
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TareasApp ?: return Result.failure()
        return try {
            when (app.sync.runNow()) {
                is SyncState.Error -> Result.retry()
                else -> Result.success() // Idle or NeedsAuth (retrying a revoked token is pointless)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "tareas-sync"

        /**
         * Enqueues (or keeps) the one unique sync work. KEEP collapses bursts:
         * mutation-triggered enqueues while a run is pending change nothing,
         * and a cycle already covers whatever is queued.
         */
        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
