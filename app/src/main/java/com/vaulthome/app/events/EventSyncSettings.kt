package com.vaulthome.app.events

import android.content.Context
import com.vaulthome.events.DeviceEntry
import com.vaulthome.events.LinkConfig
import com.vaulthome.events.LinkMode
import com.vaulthome.events.SharedConfig
import com.vaulthome.events.SharedEndpoints
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
    val connect: List<String> = DEFAULT_CONNECT_LIST,
    val mode: LinkMode = LinkMode.CLIENT,
    val listen: List<String> = listOf(DEFAULT_LISTEN),
    /** Post a "Synced notes" notification when another device's change lands in the vault. */
    val notifySynced: Boolean = true,
    /** Read and update the shared endpoint list in the vault ([SharedEndpoints.PATH]). */
    val useSharedEndpoints: Boolean = true,
    /** This phone's MagicDNS name, advertised to others in router/peer mode. */
    val tailnetName: String = DEFAULT_TAILNET_NAME,
) {
    /**
     * Zenoh session config. [connect] (the Settings "Zenoh router list") is tried first, in order.
     * When [useSharedEndpoints] is on, dialable routers and other router/peer devices from
     * [SharedEndpoints.PATH] are **appended** (not a replacement); this device's own endpoint is never dialed.
     */
    fun linkConfig(shared: SharedConfig? = null): LinkConfig = LinkConfig(
        mode = mode,
        connect = if (useSharedEndpoints) SharedEndpoints.connectList(connect, shared, selfEntry()) else connect,
        listen = if (mode == LinkMode.CLIENT) emptyList() else listen,
    )

    /** How this device describes itself in the shared file. */
    fun selfEntry(): DeviceEntry {
        val port = listen.firstNotNullOfOrNull { Regex(":([0-9]{1,5})$").find(it)?.groupValues?.get(1) } ?: "7447"
        val name = tailnetName.trim().takeIf { it.isNotEmpty() }
        return DeviceEntry(
            device = device,
            app = "VaultHome",
            mode = mode,
            listen = if (mode == LinkMode.CLIENT) emptyList() else listen,
            endpoint = if (mode != LinkMode.CLIENT && name != null) "tcp/$name:$port" else null,
            tailnetName = name,
        )
    }

    /** Why this config cannot start, or null. */
    fun problem(): String? {
        if (!DEVICE_RE.matches(device)) return "Device name: letters, digits, - _ . only (e.g. s24)"
        if (mode == LinkMode.CLIENT && connect.isEmpty()) return "Client mode needs at least one router in the Zenoh router list"
        entryProblems(connect).firstOrNull()?.let { return it.second }
        if (mode != LinkMode.CLIENT) entryProblems(listen).firstOrNull()?.let { return it.second }
        if (mode != LinkMode.CLIENT && listen.isEmpty()) return "${mode.label} mode needs a listen endpoint"
        if (tailnetName.isNotBlank() && !HOST_RE.matches(tailnetName.trim())) return "Tailscale name should look like pawels-s24-ultra.tailf52603.ts.net"
        return null
    }

    companion object {
        const val DEFAULT_DEVICE = "s24"
        const val DEFAULT_CONNECT = "tcp/note4.tailf52603.ts.net:7447"

        /** note4 plus the other tailnet hosts that could run a router (dialable entries of [SharedEndpoints.DEFAULT_ROUTERS]). */
        val DEFAULT_CONNECT_LIST: List<String> = SharedEndpoints.DEFAULT_ROUTERS.filter { it.dialable }.mapNotNull { it.endpoint }

        /** From the vault note nitro5 (tailnet peers seen 2026-10-31). */
        const val DEFAULT_TAILNET_NAME = "pawels-s24-ultra.tailf52603.ts.net"
        private val HOST_RE = Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*")
        const val DEFAULT_LISTEN = "tcp/0.0.0.0:7447"
        private val DEVICE_RE = Regex("[A-Za-z0-9._-]{1,32}")
        private val ENDPOINT_RE = Regex("(tcp|udp|tls|quic|ws)/(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):([0-9]{1,5})")

        fun endpointProblem(e: String): String? {
            val m = ENDPOINT_RE.matchEntire(e) ?: return "\"$e\" should look like tcp/host:7447 (tcp, udp, tls or quic)"
            val port = m.groupValues[3].toInt()
            if (port !in 1..65535) return "Port in \"$e\" must be 1–65535"
            return null
        }

        /** Per-entry validation of a router or listen list (index + message). */
        fun entryProblems(entries: List<String>): List<Pair<Int, String>> =
            entries.mapIndexedNotNull { i, e -> endpointProblem(e)?.let { i to it } }

        /**
         * Router list as typed in Settings: comma, semicolon, or any whitespace (including newlines).
         * Trims, drops empties, keeps first-seen order when deduping.
         */
        fun parseList(raw: String): List<String> =
            raw.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        private val SPLIT = Regex("[,;\\s]+")

        /** Persist in insertion order. A single stored value (no separators) becomes a one-element list. */
        fun encodeList(items: List<String>): String = items.joinToString(",")

        /**
         * Load the Settings router list. `null` (never stored) uses [DEFAULT_CONNECT_LIST].
         * A stored string — one endpoint or a comma/whitespace/semicolon-separated list — is parsed as-is
         * (an empty string stays empty, so a cleared list is not silently reset).
         */
        fun loadConnect(stored: String?): List<String> = if (stored == null) DEFAULT_CONNECT_LIST else parseList(stored)
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
        connect = EventSyncSettings.loadConnect(prefs.getString(KEY_CONNECT, null)),
        mode = runCatching { LinkMode.valueOf(prefs.getString(KEY_MODE, null) ?: "CLIENT") }.getOrDefault(LinkMode.CLIENT),
        listen = EventSyncSettings.parseList(prefs.getString(KEY_LISTEN, null) ?: EventSyncSettings.DEFAULT_LISTEN),
        notifySynced = prefs.getBoolean(KEY_NOTIFY, true),
        useSharedEndpoints = prefs.getBoolean(KEY_SHARED, true),
        tailnetName = prefs.getString(KEY_TAILNET, null) ?: EventSyncSettings.DEFAULT_TAILNET_NAME,
    )

    fun update(next: EventSyncSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_DEVICE, next.device)
            .putString(KEY_CONNECT, EventSyncSettings.encodeList(next.connect))
            .putString(KEY_MODE, next.mode.name)
            .putString(KEY_LISTEN, next.listen.joinToString(","))
            .putBoolean(KEY_NOTIFY, next.notifySynced)
            .putBoolean(KEY_SHARED, next.useSharedEndpoints)
            .putString(KEY_TAILNET, next.tailnetName)
            .apply()
        flow.value = next
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVICE = "device"
        const val KEY_CONNECT = "connect"
        const val KEY_MODE = "mode"
        const val KEY_LISTEN = "listen"
        const val KEY_NOTIFY = "notify_synced"
        const val KEY_SHARED = "use_shared_endpoints"
        const val KEY_TAILNET = "tailnet_name"
    }
}
