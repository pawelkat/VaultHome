package com.vaulthome.app.devices

import android.content.Context
import com.vaulthome.app.ask.Health
import com.vaulthome.app.ask.NitroGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** `/health` of every registry chat host, in parallel. URLs come from [com.vaulthome.app.ask.DeviceUrls]. */
object DeviceProbe {
    const val INTERVAL_MS = 15_000L

    suspend fun probe(
        gateway: NitroGateway,
        devices: List<RegDevice>,
        suffix: String,
        report: (String, Boolean, String) -> Unit,
    ) = coroutineScope {
        devices.filter(com.vaulthome.app.ask.DeviceUrls::isChatHost).map { d ->
            async {
                var ok = false
                var detail = "no URL"
                for (url in com.vaulthome.app.ask.DeviceUrls.resolve(d.id, suffix, d).urls()) {
                    when (val h = gateway.health(url)) {
                        Health.Ok -> { ok = true; detail = "up"; break }
                        is Health.Loading -> { ok = false; detail = "not ready (${h.detail.take(160)})" }
                        is Health.Down -> { ok = false; detail = h.reason }
                    }
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
