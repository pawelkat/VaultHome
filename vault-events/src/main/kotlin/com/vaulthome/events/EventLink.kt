package com.vaulthome.events

/** Zenoh session role. Router forwards for other devices (zenoh-kotlin has no storage plugins). */
enum class LinkMode(val wire: String) { CLIENT("client"), PEER("peer"), ROUTER("router") }

data class LinkConfig(
    val mode: LinkMode,
    /** Endpoints to dial, e.g. `tcp/note4.tailf52603.ts.net:7447`. */
    val connect: List<String>,
    /** Endpoints to listen on (peer/router), e.g. `tcp/0.0.0.0:7447`. */
    val listen: List<String> = emptyList(),
) {
    /**
     * Session config as `(key, json5)` inserts, same keys as Rust `net::router_config` /
     * `client_config`. Multicast scouting is off: peers are reached over explicit tailnet endpoints.
     */
    fun inserts(): List<Pair<String, String>> = buildList {
        add("mode" to "\"${mode.wire}\"")
        add("connect/endpoints" to jsonList(connect))
        if (mode != LinkMode.CLIENT) add("listen/endpoints" to jsonList(listen))
        add("scouting/multicast/enabled" to "false")
    }

    private fun jsonList(items: List<String>): String {
        val sb = StringBuilder("[")
        items.forEachIndexed { i, s -> if (i > 0) sb.append(','); JsonOut.string(sb, s) }
        return sb.append(']').toString()
    }
}

/** Transport used by [SyncEngine]; [ZenohLink] in production, fakes in unit tests. */
interface EventLink : AutoCloseable {
    /** Subscribe to [VaultKey.NOTES_WILDCARD]; [onSample] gets `(key, payload)` on a transport thread. */
    fun subscribe(onSample: (String, ByteArray) -> Unit)

    fun publish(ev: NoteEvent)

    /** Ask the hub for every stored stamp; `null` = no hub answered within [timeoutMs]. */
    fun fetchIndex(timeoutMs: Long): IndexReply?

    /** Stored event for one path, best of all replies; `null` = none. */
    fun fetchNote(path: String, timeoutMs: Long): NoteEvent?

    /** Routers this session is linked to right now. */
    fun routerCount(): Int

    /** Peers this session is linked to right now (router/peer mode: other routers show up as routers). */
    fun peerCount(): Int

    /**
     * Answer chunk queries on [Blob.WILDCARD] with [provider] `(sha256, index, chunkSize) -> bytes or null`
     * (called on a transport thread). Default: not served.
     */
    fun serveBlobs(provider: (String, Long, Long) -> ByteArray?) {}

    /** One chunk of blob [sha]; `null` = nobody answered within [timeoutMs]. Default: none. */
    fun fetchChunk(sha: String, index: Long, chunkSize: Long, timeoutMs: Long): ByteArray? = null
}
