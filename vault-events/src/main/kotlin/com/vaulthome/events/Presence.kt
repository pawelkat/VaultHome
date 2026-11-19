package com.vaulthome.events

/**
 * Requester identity and presence, same wire format as Rust `net.rs` / `presence.rs`: every query carries
 * `from=<device>`; each device declares the liveliness token `vault/presence/<device>` and answers a
 * queryable on the same key with [PresenceInfo] JSON.
 */
object Requester {
    const val UNKNOWN = "unknown"
    private val DEVICE = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun clean(v: String?): String = if (v != null && DEVICE.matches(v)) v else UNKNOWN

    /** `key?from=dev` or `key?a=b;from=dev`; [from] blank = unchanged (no identity configured). */
    fun withFrom(selector: String, from: String): String {
        if (from.isBlank()) return selector
        val sep = if ('?' in selector) ';' else '?'
        return "$selector${sep}from=${clean(from)}"
    }
}

object Presence {
    const val PREFIX = "vault/presence"
    const val WILDCARD = "vault/presence/*"
    const val HUB_KEY = "vault/hub/presence"

    fun key(device: String) = "$PREFIX/${Requester.clean(device)}"
}

data class PresenceInfo(val device: String, val role: String, val build: String, val startedMs: Long) {
    fun encode(): ByteArray {
        val sb = StringBuilder("{\"device\":")
        JsonOut.string(sb, device)
        sb.append(",\"role\":"); JsonOut.string(sb, role)
        sb.append(",\"build\":"); JsonOut.string(sb, build)
        sb.append(",\"started_ms\":").append(startedMs).append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/** Size and vault path of a blob being served, for the summary line. */
data class BlobLabel(val size: Long, val path: String?)

/**
 * One `served blob <sha12> (<path>) to <dev>: n/N chunks, B in s` line per (requester, blob) transfer, when
 * every chunk went out or after [idleMs] without a request (`partial ..., idle`). Mirrors Rust `ServeLog`.
 */
class ServeLog(private val idleMs: Long = 30_000L) {
    private class Transfer(val path: String?, val first: Long, var last: Long, val total: Long) {
        val have = HashSet<Long>()
        var bytes = 0L
    }

    private val open = HashMap<Pair<String, String>, Transfer>()

    /** Count one served chunk; the summary line when this completed the transfer. */
    @Synchronized
    fun record(to: String, sha: String, index: Long, len: Long, label: BlobLabel?, chunkSize: Long, now: Long): String? {
        val k = to to sha
        val total = label?.let { maxOf(1L, Blob.chunkCount(it.size, chunkSize)) } ?: 0L
        val t = open.getOrPut(k) { Transfer(label?.path, now, now, total) }
        t.last = now
        t.bytes += len
        t.have.add(index)
        if (t.total == 0L || t.have.size < t.total) return null
        open.remove(k)
        return line(k, t, true)
    }

    /** Summaries of transfers idle for [idleMs] or longer. */
    @Synchronized
    fun sweep(now: Long): List<String> {
        val idle = open.filterValues { now - it.last >= idleMs }.keys.sortedWith(compareBy({ it.first }, { it.second }))
        return idle.map { k -> line(k, open.remove(k)!!, false) }
    }

    @get:Synchronized val openTransfers: Int get() = open.size

    private fun line(k: Pair<String, String>, t: Transfer, complete: Boolean): String {
        val name = t.path?.let { " ($it)" } ?: ""
        val secs = String.format(java.util.Locale.ROOT, "%.1f", (t.last - t.first) / 1000.0)
        val total = if (t.total > 0) t.total.toString() else "?"
        val head = "served blob ${k.second.take(12)}$name to ${k.first}: "
        return if (complete) "$head${t.have.size}/$total chunks, ${t.bytes} B in $secs s"
        else "${head}partial ${t.have.size}/$total chunks, ${t.bytes} B in $secs s, idle"
    }
}
