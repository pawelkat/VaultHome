package com.vaulthome.app.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vaulthome.app.MainActivity
import com.vaulthome.app.R
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.events.AgentState
import com.vaulthome.events.Disk
import com.vaulthome.events.SharedEndpoints
import com.vaulthome.events.EngineOptions
import com.vaulthome.events.EngineStatus
import com.vaulthome.events.LinkMode
import com.vaulthome.events.SyncCore
import com.vaulthome.events.SyncEngine
import com.vaulthome.events.ZenohLink
import com.vaulthome.events.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Service state for Settings: engine status plus why it is not running. */
data class EventSyncState(
    val running: Boolean = false,
    val status: EngineStatus = EngineStatus(),
    val error: String? = null,
    val endpoint: String? = null,
)

/**
 * Foreground service that keeps one Zenoh session (client, peer or router) and a [SyncEngine]
 * applying note events (vault/notes/ wildcard) to VaultHome's SAF vault tree and publishing local changes found
 * by polling it. Type specialUse on Android 14+ (no 6-hour dataSync limit), dataSync before that.
 */
class EventSyncService : Service() {
    private var engine: SyncEngine? = null
    private var running: EventSyncSettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            graph().eventSync.update(graph().eventSync.state.value.copy(enabled = false))
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val settings = graph().eventSync.state.value
        startInForeground("Starting…")
        if (intent?.action == ACTION_SYNC_NOW && engine != null && running == settings) {
            engine?.catchUpNow()
            return START_STICKY
        }
        if (engine != null && running == settings) return START_STICKY
        stopEngine()
        val problem = when {
            !settings.enabled -> "Event sync is off"
            else -> settings.problem() ?: vaultProblem()
        }
        if (problem != null) {
            mutable.value = EventSyncState(running = false, error = problem)
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startEngine(settings)
        } catch (e: Throwable) {
            Log.w(TAG, "cannot start event sync", e)
            mutable.value = EventSyncState(running = false, error = "Cannot start: ${e.message ?: e.javaClass.simpleName}")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun graph() = (application as VaultHomeApp).graph

    private fun vaultProblem(): String? {
        val tree = graph().store.treeUri() ?: return "Grant the vault folder first"
        val perm = contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
            ?: return "Vault grant is missing; pick the vault again"
        if (!perm.isWritePermission) return "Event sync writes notes: grant write access to the vault (Settings → Event sync)"
        return null
    }

    private fun startEngine(settings: EventSyncSettings) {
        val g = graph()
        val tree = g.store.treeUri()!!
        val fs = SafVaultTree(this, tree)
        // One state file per (device, vault): renaming the device or switching vault starts fresh.
        val stateFile = File(filesDir, "vault-events/state-${sha256Hex("${settings.device}\n$tree".toByteArray()).take(16)}.json")
        val state = AgentState.loadOrNew(stateFile, settings.device, tree.toString())
        val core = SyncCore(fs, settings.device, state, stateFile)
        runCatching { io.zenoh.Zenoh.initLogFromEnvOr("warn") }
        val link = settings.linkConfig()
        val notifier = SyncedNotesNotifier(this).also { it.ensureChannel() }
        fun endpoint(connect: List<String>) = when (settings.mode) {
            LinkMode.CLIENT -> connect.joinToString()
            else -> "listen ${settings.listen.joinToString()}" + if (connect.isNotEmpty()) ", connect ${connect.joinToString()}" else ""
        }
        val e = SyncEngine(
            core = core,
            config = link,
            // Identity: `from=<device>` on every query, `vault/presence/<device>` with this build.
            openLink = { ZenohLink.open(it.copy(device = settings.device, build = BUILD)) },
            // Large files: partial chunk downloads live in the app cache, never in the SAF vault tree.
            options = EngineOptions(partialDir = File(cacheDir, "vault-events-partial")),
            onStatus = { st ->
                mutable.value = EventSyncState(running = true, status = st, endpoint = endpoint(st.connect.ifEmpty { link.connect }))
                notifyStatus(settings, st)
            },
            onVaultChanged = { g.reader.invalidate() },
            log = { Log.i(TAG, it) },
            // Shared endpoint list in the vault: read before each connect, self entry merged after the first catch-up
            // (so the hub's newer copy is applied first and other devices' entries are kept).
            resolveConfig = { cfg ->
                if (!settings.useSharedEndpoints) cfg else settings.linkConfig(SharedEndpoints.parse((fs.read(SharedEndpoints.PATH) as? Disk.Present)?.bytes))
            },
            onConnected = {
                if (settings.useSharedEndpoints) {
                    val disk = fs.read(SharedEndpoints.PATH)
                    if (disk !is Disk.Skipped) {
                        val next = SharedEndpoints.mergeSelf((disk as? Disk.Present)?.bytes, settings.selfEntry(), System.currentTimeMillis())
                        if (next != null) {
                            // Written like a local edit: the initial scan right after this publishes it,
                            // labelled <device>/vaulthome (the app's own write), not <device>/external.
                            it.noteOwnWrite(SharedEndpoints.PATH, next)
                            fs.writeAtomic(SharedEndpoints.PATH, next)
                            Log.i(TAG, "shared endpoints: wrote ${settings.device} entry")
                        }
                    }
                }
            },
            onNotices = { n -> if (settings.notifySynced) notifier.post(n) },
            // TowerX power board + TowerX agent tokens for Settings and Ask (vault note `Grok Bot - TowerX power`).
            watchTokens = com.vaulthome.app.towerx.TowerXKeys.WATCH,
            onToken = com.vaulthome.app.towerx.TowerXMonitor::onToken,
        )
        engine = e
        com.vaulthome.app.towerx.TowerXMonitor.caller = { sel, payload, ms -> e.call(sel, payload, ms) }
        running = settings
        if (settings.mode == LinkMode.ROUTER) acquireWakeLock() else releaseWakeLock()
        mutable.value = EventSyncState(running = true, status = e.status, endpoint = endpoint(link.connect))
        e.start()
    }

    private fun summary(s: EventSyncSettings, st: EngineStatus): String {
        val links = "${st.routers} router${if (st.routers == 1) "" else "s"}, ${st.peers} peer${if (st.peers == 1) "" else "s"}"
        return when (st.phase) {
            EngineStatus.Phase.CONNECTED -> "${s.mode.label} · $links · ${st.tracked} notes"
            EngineStatus.Phase.CONNECTING -> "${s.mode.label} · connecting…"
            EngineStatus.Phase.ERROR -> "${s.mode.label} · error"
            EngineStatus.Phase.STOPPED -> "stopped"
        }
    }

    private fun notifyStatus(s: EventSyncSettings, st: EngineStatus) {
        val text = summary(s, st)
        if (text == lastNotified) return
        lastNotified = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EventSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("VaultHome event sync")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Vault event sync", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    /** Router mode serves other devices: keep the CPU awake so links are not dropped while the screen is off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VaultHome:eventsRouter")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopEngine() {
        com.vaulthome.app.towerx.TowerXMonitor.caller = null
        com.vaulthome.app.towerx.TowerXMonitor.onSessionClosed()
        engine?.stop()
        engine = null
        running = null
    }

    private fun shutdown() {
        stopEngine()
        releaseWakeLock()
        if (mutable.value.running) mutable.value = mutable.value.copy(running = false, status = mutable.value.status.copy(phase = EngineStatus.Phase.STOPPED))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        /** `vaulthome <versionName>+<git sha>` for the presence reply. */
        val BUILD = "vaulthome ${com.vaulthome.app.BuildConfig.VERSION_NAME}+${com.vaulthome.app.BuildConfig.GIT_SHA}"
        private const val TAG = "EventSync"
        const val CHANNEL_ID = "vault_events"
        private const val NOTIFICATION_ID = 4005
        private const val ACTION_STOP = "com.vaulthome.app.events.STOP"
        private const val ACTION_SYNC_NOW = "com.vaulthome.app.events.SYNC_NOW"

        private val mutable = MutableStateFlow(EventSyncState())
        val state: StateFlow<EventSyncState> = mutable

        /** Start or restart with the current settings (no-op restart if unchanged). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, EventSyncService::class.java))
            }.onFailure { mutable.value = EventSyncState(running = false, error = it.message ?: "could not start") }
        }

        fun syncNow(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, EventSyncService::class.java).setAction(ACTION_SYNC_NOW))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EventSyncService::class.java))
            mutable.value = mutable.value.copy(running = false, status = mutable.value.status.copy(phase = EngineStatus.Phase.STOPPED))
        }
    }
}

/** Bring event sync back after a reboot when it was enabled (specialUse may start from BOOT_COMPLETED). */
class EventSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as VaultHomeApp).graph
        if (graph.eventSync.state.value.enabled) EventSyncService.start(context)
    }
}
