package com.vaulthome.events

/** Zenoh session role. Router forwards for other devices (zenoh-kotlin has no storage plugins). */
enum class LinkMode(val wire: String) { CLIENT("client"), PEER("peer"), ROUTER("router") }

data class LinkConfig(
    val mode: LinkMode,
    /** Endpoints to dial, e.g. `tcp/note4.tailf52603.ts.net:7447`. */
    val connect: List<String>,
    /** Endpoints to listen on (peer/router), e.g. `tcp/0.0.0.0:7447`. */
    val listen: List<String> = emptyList(),
    /** This device: sent as `from=` on every query and announced as `vault/presence/<device>`; blank = neither. */
    val device: String = "",
    /** Build string in the presence reply, e.g. `vaulthome 0.1.0+abc1234`. */
    val build: String = "",
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

    /** Hub history for [path] (newest first), up to [limit] entries; `null` = no hub answered. */
    fun fetchHistory(path: String, limit: Int, timeoutMs: Long): HistoryReply? = null

    /**
     * Subscribe to the hub liveliness token on [Presence.HUB_KEY] (history on, so a token already
     * present is delivered). [onChange] is `true` when the hub appears, `false` when it leaves.
     * Default: no-op.
     */
    fun subscribeHubPresence(onChange: (Boolean) -> Unit): AutoCloseable? = null

    /**
     * Liveliness subscriber on [keyExpr] (history on): [onChange] gets `(key, alive)`. Used for device tokens
     * such as `devices/towerx-power` (ESP32) and `vault/presence/towerx` (TowerX agent). `null` = unsupported.
     */
    fun subscribeLiveliness(keyExpr: String, onChange: (String, Boolean) -> Unit): AutoCloseable? = null

    /** One query on [selector] with an optional UTF-8 [payload]; first reply as text, `null` = no reply. */
    fun call(selector: String, payload: String?, timeoutMs: Long): String? = null

    /** Routers this session is linked to right now. */
    fun routerCount(): Int

    /** Peers this session is linked to right now (router/peer mode: other routers show up as routers). */
    fun peerCount(): Int

    /**
     * Answer chunk queries on [Blob.WILDCARD] addressed to [device] (or `any` / no `src`) with [provider]
     * `(sha256, index, chunkSize) -> bytes or null`, on a transport thread (never behind the worker).
     * [describe] gives a blob's size and path; [log] gets one `served blob ... to <requester>` line per
     * transfer. Default: not served.
     */
    fun serveBlobs(
        device: String,
        provider: (String, Long, Long) -> ByteArray?,
        describe: (String) -> BlobLabel? = { null },
        log: (String) -> Unit = {},
    ) {}

    /**
     * One chunk of blob [sha] from the server named [src] (`hub`, a device, `any`); `null` = nobody answered
     * within [timeoutMs]. Returns on the first reply. Default: none.
     */
    fun fetchChunk(sha: String, index: Long, chunkSize: Long, timeoutMs: Long, src: String = Blob.SRC_ANY): ByteArray? = null

    /**
     * Publish a transfer ack on `vault/ack/<sha256>/<this device>`. Default: no-op (unit-test
     * fakes override when they need to observe it).
     */
    fun publishAck(sha256: String, ack: TransferAck) {}
}
