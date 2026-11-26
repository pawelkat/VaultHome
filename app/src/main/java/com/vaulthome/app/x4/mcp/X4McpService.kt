package com.vaulthome.app.x4.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vaulthome.app.MainActivity
import com.vaulthome.app.R
import com.vaulthome.app.VaultHomeApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What Settings shows about the server. */
data class X4McpServerState(val running: Boolean = false, val port: Int? = null, val error: String? = null)

/**
 * Foreground service that keeps the display MCP listening ("VaultHome X4 MCP on port N").
 * Type connectedDevice: it exists to drive the X4 over Bluetooth LE.
 */
class X4McpService : Service() {
    private var server: X4McpHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val graph = (application as VaultHomeApp).graph
        val port = graph.x4Settings.server.value.port
        startInForeground(port)
        val current = server
        if (current != null && current.isRunning && current.port == port) return START_STICKY
        current?.stop()
        val handler = X4McpHandler(gateway = graph.x4, settings = { graph.x4Settings.state.value })
        val next = X4McpHttpServer(handler, port, log = { Log.i(TAG, it) })
        try {
            next.start()
            server = next
            mutable.value = X4McpServerState(running = true, port = port)
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on $port", e)
            server = null
            mutable.value = X4McpServerState(running = false, port = port, error = "Port $port: ${e.message ?: "cannot listen"}")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startInForeground(port: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "X4 MCP server", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, X4McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("VaultHome X4 MCP on port $port")
            .setContentText("Grok Bot can show notes on the X4 (forwarded over Bluetooth)")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun shutdown() {
        server?.stop()
        server = null
        mutable.value = X4McpServerState(running = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (mutable.value.running) mutable.value = X4McpServerState(running = false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "X4McpService"
        const val CHANNEL_ID = "x4_mcp"
        private const val NOTIFICATION_ID = 4004
        private const val ACTION_STOP = "com.vaulthome.app.x4.mcp.STOP"

        private val mutable = MutableStateFlow(X4McpServerState())
        val state: StateFlow<X4McpServerState> = mutable

        /** Start (or restart on a new port). Call from the foreground UI. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, X4McpService::class.java))
            }.onFailure { mutable.value = X4McpServerState(running = false, error = it.message ?: "could not start") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, X4McpService::class.java))
            mutable.value = X4McpServerState(running = false)
        }
    }
}
