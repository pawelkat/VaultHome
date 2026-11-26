package com.vaulthome.app.devices

import android.content.Context
import com.vaulthome.app.ask.Health
import com.vaulthome.app.ask.NitroGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** `/health` of every registry device that has a chat proxy, in parallel (the existing probe, driven by the registry). */
object DeviceProbe {
    const val INTERVAL_MS = 15_000L

    suspend fun probe(gateway: NitroGateway, devices: List<RegDevice>, report: (String, Boolean, String) -> Unit) = coroutineScope {
        devices.filter { it.chatProxy != null }.map { d ->
            async {
                val (ok, detail) = when (val h = gateway.health(d.chatProxy!!)) {
                    Health.Ok -> true to "up"
                    is Health.Loading -> false to "not ready (${h.detail.take(160)})"
                    is Health.Down -> false to h.reason
                }
                report(d.id, ok, detail)
            }
        }.forEach { it.await() }
    }
}

/** Last time a device was seen online (kept across restarts so the page can say "last seen 3 h ago"). */
class DeviceSeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome_devices_seen", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
    val state: StateFlow<Map<String, Long>> = flow

    fun seen(id: String, atMs: Long) {
        prefs.edit().putLong(id, atMs).apply()
        flow.update { it + (id to atMs) }
    }
}

/** Opens the Devices page (top bar icon, Providers page, Settings link). One global flag, like [com.vaulthome.app.ask.ProvidersNav]. */
object DevicesNav {
    private val mutable = MutableStateFlow(false)
    val open: StateFlow<Boolean> = mutable
    fun show() { mutable.value = true }
    fun hide() { mutable.value = false }
}
