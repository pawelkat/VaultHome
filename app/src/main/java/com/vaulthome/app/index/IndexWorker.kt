package com.vaulthome.app.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vaulthome.app.R
import com.vaulthome.app.VaultHomeApp

class IndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val indexer = (applicationContext as VaultHomeApp).graph.indexer
        return try {
            try {
                setForeground(getForegroundInfo())
            } catch (_: Exception) {
                // Index still runs if the foreground notification cannot start.
            }
            indexer.indexAll()
            Result.success()
        } catch (error: Exception) {
            (applicationContext as VaultHomeApp).graph.status.update(
                IndexProgress(phase = "error", message = error.message ?: "Index failed"),
            )
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("VaultHome")
            .setContentText("Indexing the vault")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "vault-index"
        private const val NOTIFICATION_ID = 42
        const val UNIQUE_NAME = "vault-index"
    }
}
