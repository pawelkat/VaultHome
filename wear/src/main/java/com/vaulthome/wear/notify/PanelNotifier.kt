package com.vaulthome.wear.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vaulthome.panel.PanelDedupe
import com.vaulthome.panel.PanelFormat
import com.vaulthome.wear.R
import com.vaulthome.wear.data.PanelStore
import com.vaulthome.wear.data.StoredPanel
import com.vaulthome.wear.presentation.PanelActivity

/** One watch-local notification, updated in place; posted only when the panel hash is new. */
object PanelNotifier {
    const val CHANNEL_ID = "panel-daily"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyIfNew(context: Context, store: PanelStore, panel: StoredPanel) {
        if (!PanelDedupe.shouldNotify(store.lastNotifiedHash, panel.hash)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Not marked as notified: posts once the user grants it in the watch app.
        }
        ensureChannel(context)
        val note = panel.note
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_panel)
            .setContentTitle(PanelFormat.notificationTitle(note))
            .setContentText(PanelFormat.collapsedText(note))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PanelFormat.fullText(note)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setLocalOnly(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            store.lastNotifiedHash = panel.hash
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
