package com.vaulthome.app.events

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vaulthome.app.MainActivity
import com.vaulthome.app.R
import com.vaulthome.events.Notice
import com.vaulthome.events.SyncNotices
import com.vaulthome.events.SyncedNote

/**
 * "Synced notes" notifications (own channel, separate from the ongoing event-sync notification).
 * Singles share a group, so Android bundles them; a batch over [SyncNotices.MAX_INDIVIDUAL] notes
 * (typically a catch-up) is one "N notes synced" notification. Tapping opens the note in VaultHome.
 */
class SyncedNotesNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Synced notes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A note changed on another device and was synced into the vault"
            },
        )
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return nm.areNotificationsEnabled()
    }

    fun post(notices: List<Notice>) {
        if (notices.isEmpty() || !canPost()) return
        ensureChannel()
        for (n in notices) {
            when (n) {
                is Notice.Single -> postSingle(n.note)
                is Notice.Summary -> postSummary(n)
            }
        }
        // Group summary so several singles collapse into one bundle.
        val active = nm.activeNotifications.count { it.notification.group == GROUP && it.id != SUMMARY_ID }
        if (active > 1) {
            nm.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Synced notes")
                    .setContentText("$active notes synced")
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setContentIntent(openIntent(null, SUMMARY_ID))
                    .build(),
            )
        }
    }

    private fun postSingle(note: SyncedNote) {
        val (title, text) = SyncNotices.singleText(note)
        val id = idFor(note.path)
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(note.origin)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(openIntent(if (note.deleted) null else note.path, id))
                .build(),
        )
    }

    private fun postSummary(s: Notice.Summary) {
        val style = NotificationCompat.InboxStyle()
        s.notes.take(6).forEach { style.addLine(SyncNotices.singleText(it).let { (t, _) -> "$t · ${it.origin}" }) }
        if (s.count > 6) style.setSummaryText("+${s.count - 6} more")
        val title = SyncNotices.summaryTitle(s)
        // Opening a single live note is useful; for a batch, open the newest one that still exists.
        val target = s.notes.lastOrNull { !it.deleted }?.path
        nm.notify(
            BATCH_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(if (s.catchUp) "Catch-up from the hub" else s.notes.joinToString { it.title })
                .setStyle(style.setBigContentTitle(title))
                .setNumber(s.count)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(openIntent(target, BATCH_ID))
                .build(),
        )
    }

    private fun openIntent(path: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (path != null) intent.putExtra(MainActivity.EXTRA_OPEN_NOTE, path).setAction(ACTION_OPEN_NOTE)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "vault_synced_notes"
        private const val GROUP = "com.vaulthome.app.SYNCED_NOTES"
        private const val SUMMARY_ID = 4006
        private const val BATCH_ID = 4007
        private const val ACTION_OPEN_NOTE = "com.vaulthome.app.OPEN_NOTE"

        /** Stable per path so a note edited twice updates one notification. */
        fun idFor(path: String): Int = 10_000 + (path.hashCode() and 0x3FFFFFF)
    }
}
