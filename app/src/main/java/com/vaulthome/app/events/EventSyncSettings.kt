package com.vaulthome.app.events

import android.content.Context
import com.vaulthome.events.LinkConfig
import com.vaulthome.events.LinkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Event sync over Zenoh (vault notes "Vault Event Sync Plan" / "Vault Event Sync Architecture").
 * Off by default. Router mode is offered because zenoh-kotlin 1.10.2's JNI build includes the
 * router routing code (no storage plugins: the box hub keeps the notes).
 */
data class EventSyncSettings(
    val enabled: Boolean = false,
    val device: String = DEFAULT_DEVICE,
    val connect: List<String> = listOf(DEFAULT_CONNECT),
    val mode: LinkMode = LinkMode.CLIENT,
    val listen: List<String> = listOf(DEFAULT_LISTEN),
) {
    fun linkConfig(): LinkConfig = LinkConfig(
        mode = mode,
        connect = connect,
        listen = if (mode == LinkMode.CLIENT) emptyList() else listen,
    )

    /** Why this config cannot start, or null. */
    fun problem(): String? {
        if (!DEVICE_RE.matches(device)) return "Device name: letters, digits, - _ . only (e.g. s24)"
        if (mode == LinkMode.CLIENT && connect.isEmpty()) return "Client mode needs at least one connect endpoint"
        (connect + if (mode == LinkMode.CLIENT) emptyList() else listen).firstOrNull { endpointProblem(it) != null }?.let {
            return endpointProblem(it)
        }
        if (mode != LinkMode.CLIENT && listen.isEmpty()) return "${mode.label} mode needs a listen endpoint"
        return null
    }

    companion object {
        const val DEFAULT_DEVICE = "s24"
        const val DEFAULT_CONNECT = "tcp/note4.tailf52603.ts.net:7447"
        const val DEFAULT_LISTEN = "tcp/0.0.0.0:7447"
        private val DEVICE_RE = Regex("[A-Za-z0-9._-]{1,32}")
        private val ENDPOINT_RE = Regex("(tcp|udp|tls|quic|ws)/(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):([0-9]{1,5})")

        fun endpointProblem(e: String): String? {
            val m = ENDPOINT_RE.matchEntire(e) ?: return "Endpoint \"$e\" should look like tcp/host:7447"
            val port = m.groupValues[3].toInt()
            if (port !in 1..65535) return "Port in \"$e\" must be 1–65535"
            return null
        }

        /** Comma / whitespace separated list as typed in Settings. */
        fun parseList(raw: String): List<String> = raw.split(',', '\n', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

val LinkMode.label: String get() = when (this) {
    LinkMode.CLIENT -> "Client"
    LinkMode.PEER -> "Peer"
    LinkMode.ROUTER -> "Router"
}

class EventSyncSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome_events", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<EventSyncSettings> = flow

    private fun load() = EventSyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        device = prefs.getString(KEY_DEVICE, null) ?: EventSyncSettings.DEFAULT_DEVICE,
        connect = EventSyncSettings.parseList(prefs.getString(KEY_CONNECT, null) ?: EventSyncSettings.DEFAULT_CONNECT),
        mode = runCatching { LinkMode.valueOf(prefs.getString(KEY_MODE, null) ?: "CLIENT") }.getOrDefault(LinkMode.CLIENT),
        listen = EventSyncSettings.parseList(prefs.getString(KEY_LISTEN, null) ?: EventSyncSettings.DEFAULT_LISTEN),
    )

    fun update(next: EventSyncSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_DEVICE, next.device)
            .putString(KEY_CONNECT, next.connect.joinToString(","))
            .putString(KEY_MODE, next.mode.name)
            .putString(KEY_LISTEN, next.listen.joinToString(","))
            .apply()
        flow.value = next
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVICE = "device"
        const val KEY_CONNECT = "connect"
        const val KEY_MODE = "mode"
        const val KEY_LISTEN = "listen"
    }
}
