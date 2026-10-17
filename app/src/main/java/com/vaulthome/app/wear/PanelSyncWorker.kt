package com.vaulthome.app.wear

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vaulthome.app.VaultHomeApp
import java.util.concurrent.TimeUnit

/** Checks today's Panel note and publishes it to the watch when its content hash changed. */
class PanelSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as VaultHomeApp).graph.panelSync
        sync.sync(force = inputData.getBoolean(KEY_FORCE, false))
        // A missing file or absent watch is normal; the next run tries again.
        return Result.success()
    }

    companion object {
        private const val KEY_FORCE = "force"
        const val PERIODIC_NAME = "panel-sync"
        const val NOW_NAME = "panel-sync-now"

        /** App start: one forced run now, plus the 30-minute periodic check (kept if already scheduled). */
        fun schedule(context: Context) {
            // WorkManager is not initialised in JVM (Robolectric) tests that start the Application.
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            work.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PanelSyncWorker>(30, TimeUnit.MINUTES).build(),
            )
            work.enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PanelSyncWorker>()
                    .setInputData(workDataOf(KEY_FORCE to true))
                    .build(),
            )
        }
    }
}
