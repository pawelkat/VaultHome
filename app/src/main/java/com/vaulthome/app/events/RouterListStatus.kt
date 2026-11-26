package com.vaulthome.app.events

import com.vaulthome.events.EngineStatus
import com.vaulthome.events.LinkMode
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

/**
 * How Settings describes the Zenoh router list.
 *
 * Zenoh 1.10 client mode only keeps a link to one router (see DEFAULT_CONFIG.json5:
 * "the client mode only allows connecting to a single endpoint"). The list is a
 * failover order, not simultaneous sessions. [EngineStatus.routers] is the live
 * `session.info().routersZid()` count — typically 1 for a client — not how many
 * hosts were typed. SessionInfo has no locators, so a row cannot be marked
 * "the linked one"; TCP reachability is shown instead.
 */
object RouterListStatus {
    data class HostPort(val proto: String, val host: String, val port: Int)

    enum class Reach { CHECKING, REACHABLE, UNREACHABLE }

    /** `tcp/host:7447`, `tcp/[fd7a::1]:7447`. */
    fun parse(endpoint: String): HostPort? {
        val m = ENDPOINT.matchEntire(endpoint.trim()) ?: return null
        val port = m.groupValues[3].toInt()
        if (port !in 1..65535) return null
        return HostPort(m.groupValues[1], m.groupValues[2], port)
    }

    /** First DNS label, or the raw host for an address. */
    fun shortName(host: String): String {
        if (host.startsWith("[")) return host
        if (host.all { it.isDigit() || it == '.' }) return host
        if (host.contains(':')) return host
        return host.substringBefore('.')
    }

    fun rowLabel(reach: Reach?): String = when (reach) {
        null, Reach.CHECKING -> "checking…"
        Reach.REACHABLE -> "reachable"
        Reach.UNREACHABLE -> "unreachable"
    }

    /**
     * Live linked count vs how many endpoints were actually dialled.
     * [configured] is the merged Settings + shared list (what Zenoh received).
     */
    fun linkedPhrase(mode: LinkMode?, linked: Int, configured: Int, fromVault: Int = 0): String {
        val of = if (configured > 0) " of $configured" else ""
        val noun = if (max(linked, configured) == 1) "router linked" else "routers linked"
        val bits = buildList {
            if (fromVault > 0) add("$fromVault from vault")
            if (mode == LinkMode.CLIENT && configured > 1) add("client uses one at a time")
        }
        val note = if (bits.isEmpty()) "" else " (${bits.joinToString("; ")})"
        return "$linked$of $noun$note"
    }

    fun statusLine(
        enabled: Boolean,
        state: EventSyncState,
        fallbackConnect: List<String> = emptyList(),
        fromVault: Int = state.fromVault,
    ): String {
        if (!enabled) return "Status: off"
        if (!state.running) return "Status: not running"
        val st = state.status
        val phase = when (st.phase) {
            EngineStatus.Phase.CONNECTED -> "connected"
            EngineStatus.Phase.CONNECTING -> "connecting"
            EngineStatus.Phase.ERROR -> "error"
            EngineStatus.Phase.STOPPED -> "stopped"
        }
        val configured = st.connect.ifEmpty { fallbackConnect }.size
        return "Status: $phase · ${linkedPhrase(st.mode, st.routers, configured, fromVault)} · ${st.peers} peers · " +
            "${st.tracked} notes tracked · ${st.published} sent · ${st.applied} applied · ${st.conflicts} conflicts"
    }

    fun notificationSummary(mode: LinkMode, st: EngineStatus, fromVault: Int = 0): String {
        val configured = st.connect.size
        val links = "${linkedPhrase(st.mode ?: mode, st.routers, configured, fromVault)}, " +
            "${st.peers} peer${if (st.peers == 1) "" else "s"}"
        return when (st.phase) {
            EngineStatus.Phase.CONNECTED -> "${mode.label} · $links · ${st.tracked} notes"
            EngineStatus.Phase.CONNECTING -> "${mode.label} · connecting…"
            EngineStatus.Phase.ERROR -> "${mode.label} · error"
            EngineStatus.Phase.STOPPED -> "stopped"
        }
    }

    private val ENDPOINT = Regex("(tcp|udp|tls|quic|ws)/(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):([0-9]{1,5})")
}

/** Short TCP connect to a Zenoh listen port. Not a session open — only "is something listening". */
object RouterProbe {
    const val INTERVAL_MS = 15_000L
    const val TIMEOUT_MS = 2_000

    fun tcpOpen(host: String, port: Int, timeoutMs: Int = TIMEOUT_MS): Boolean =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.isConnected
            }
        }.getOrDefault(false)

    fun tcpOpen(endpoint: String, timeoutMs: Int = TIMEOUT_MS): Boolean {
        val hp = RouterListStatus.parse(endpoint) ?: return false
        return tcpOpen(hp.host, hp.port, timeoutMs)
    }
}
