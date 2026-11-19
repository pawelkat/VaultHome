package com.vaulthome.events

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.handlers.Callback
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.Subscriber
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.QoS
import io.zenoh.query.Query
import io.zenoh.query.Queryable
import io.zenoh.query.ConsolidationMode
import io.zenoh.query.QueryTarget
import io.zenoh.query.Reply
import io.zenoh.query.Selector
import io.zenoh.sample.Sample
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [EventLink] over zenoh-kotlin 1.10.2 (same Zenoh release as the box hub). Mirrors Rust `net.rs`:
 * puts block under congestion, catch-up queries use target ALL + consolidation NONE.
 */
class ZenohLink private constructor(private val session: Session, private val device: String) : EventLink {
    private var subscriber: Subscriber<Unit>? = null
    private var blobQueryable: Queryable<Unit>? = null
    private var presenceToken: LivelinessToken? = null
    private var presenceQueryable: Queryable<Unit>? = null
    private var sweeper: ScheduledExecutorService? = null

    override fun subscribe(onSample: (String, ByteArray) -> Unit) {
        val ke = KeyExpr.tryFrom(VaultKey.NOTES_WILDCARD).getOrThrow()
        subscriber = session.declareSubscriber(ke, Callback<Sample> { s ->
            onSample(s.keyExpr.toString(), s.payload.toBytes())
        }).getOrThrow()
    }

    override fun publish(ev: NoteEvent) {
        val ke = KeyExpr.tryFrom(ev.key()).getOrThrow()
        session.put(ke, ZBytes.from(ev.encode()), qos = QoS(congestionControl = CongestionControl.BLOCK)).getOrThrow()
    }

    private fun query(key: String, timeoutMs: Long, onReply: (Sample) -> Unit) {
        val done = CountDownLatch(1)
        val sel = if (device.isBlank()) Selector(KeyExpr.tryFrom(key).getOrThrow())
        else Selector.tryFrom(Requester.withFrom(key, device)).getOrThrow()
        session.get(
            sel,
            callback = Callback<Reply> { r -> r.result.onSuccess(onReply) },
            timeout = Duration.ofMillis(timeoutMs),
            target = QueryTarget.ALL,
            consolidation = ConsolidationMode.NONE,
            onClose = { done.countDown() },
        ).getOrThrow()
        done.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
    }

    override fun fetchIndex(timeoutMs: Long): IndexReply? {
        var out: IndexReply? = null
        query(VaultKey.INDEX_KEY, timeoutMs) { s ->
            if (out == null) out = runCatching { IndexReply.decode(s.payload.toBytes()) }.getOrNull()
        }
        return out
    }

    override fun fetchNote(path: String, timeoutMs: Long): NoteEvent? {
        var best: NoteEvent? = null
        query(VaultKey.pathToKey(path), timeoutMs) { s ->
            val ev = runCatching { NoteEvent.decode(s.payload.toBytes(), s.keyExpr.toString()) }.getOrNull() ?: return@query
            synchronized(this) { if (best?.let { ev.stamp.beats(it.stamp) } != false) best = ev }
        }
        return best
    }

    override fun serveBlobs(
        device: String,
        provider: (String, Long, Long) -> ByteArray?,
        describe: (String) -> BlobLabel?,
        log: (String) -> Unit,
    ) {
        val ke = KeyExpr.tryFrom(Blob.WILDCARD).getOrThrow()
        val served = ServeLog()
        blobQueryable = session.declareQueryable(ke, Callback<Query> { q ->
            q.use {
                val params = it.parameters
                if (!Blob.agentAnswers(device, runCatching { params?.get("src") }.getOrNull())) return@use
                val k = it.keyExpr.toString()
                val (sha, i) = Blob.parseKey(k) ?: return@use
                val cs = Blob.chunkSizeParam(runCatching { params?.get("cs") }.getOrNull())
                val bytes = runCatching { provider(sha, i, cs) }.getOrNull() ?: return@use
                val to = Requester.clean(runCatching { params?.get("from") }.getOrNull())
                if (runCatching { it.reply(it.keyExpr, ZBytes.from(bytes)) }.isFailure) return@use
                served.record(to, sha, i, bytes.size.toLong(), runCatching { describe(sha) }.getOrNull(), cs, System.currentTimeMillis())
                    ?.let(log)
            }
        }).getOrThrow()
        sweeper?.shutdownNow()
        sweeper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vault-events-servelog").apply { isDaemon = true } }
            .also { ex ->
                ex.scheduleWithFixedDelay({ runCatching { served.sweep(System.currentTimeMillis()).forEach(log) } }, 5, 5, TimeUnit.SECONDS)
            }
    }

    /** Declare `vault/presence/<device>` (liveliness token) and answer it with [info]. */
    private fun announce(info: PresenceInfo) {
        val ke = KeyExpr.tryFrom(Presence.key(info.device)).getOrThrow()
        presenceToken = session.liveliness().declareToken(ke).getOrThrow()
        val body = info.encode()
        presenceQueryable = session.declareQueryable(ke, Callback<Query> { q ->
            q.use { runCatching { it.reply(it.keyExpr, ZBytes.from(body)) } }
        }).getOrThrow()
    }

    /**
     * One chunk from the server named [src]: target ALL, priority DATA_LOW, congestion control BLOCK (replies
     * inherit both, so a reply crossing a slow router is not dropped). Returns on the first non-empty reply;
     * the query's final message (or its timeout) only matters when nobody has the chunk.
     */
    override fun fetchChunk(sha: String, index: Long, chunkSize: Long, timeoutMs: Long, src: String): ByteArray? {
        val out = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        val sel = Selector.tryFrom(Requester.withFrom(Blob.selector(sha, index, chunkSize, src), device)).getOrThrow()
        session.get(
            sel,
            callback = Callback<Reply> { r ->
                r.result.onSuccess { s ->
                    val b = s.payload.toBytes()
                    if (b.isNotEmpty() && out.compareAndSet(null, b)) done.countDown()
                }
            },
            timeout = Duration.ofMillis(timeoutMs),
            target = QueryTarget.ALL,
            consolidation = ConsolidationMode.NONE,
            onClose = { done.countDown() },
            qos = QoS(congestionControl = CongestionControl.BLOCK, priority = Priority.DATA_LOW),
        ).getOrThrow()
        done.await(timeoutMs + 1_000, TimeUnit.MILLISECONDS)
        return out.get()
    }

    override fun routerCount(): Int = session.info().routersZid().getOrNull()?.size ?: 0

    override fun peerCount(): Int = session.info().peersZid().getOrNull()?.size ?: 0

    override fun close() {
        runCatching { sweeper?.shutdownNow() }
        sweeper = null
        runCatching { presenceToken?.close() }
        presenceToken = null
        runCatching { presenceQueryable?.close() }
        presenceQueryable = null
        runCatching { subscriber?.close() }
        subscriber = null
        runCatching { blobQueryable?.close() }
        blobQueryable = null
        runCatching { session.close() }
    }

    companion object {
        /** Open a session (client mode fails when no router is reachable, like the Rust agent). */
        fun open(cfg: LinkConfig): ZenohLink {
            val c = Config.default()
            for ((k, v) in cfg.inserts()) c.insertJson5(k, v).getOrThrow()
            val link = ZenohLink(Zenoh.open(c).getOrThrow(), cfg.device)
            if (cfg.device.isNotBlank()) {
                val info = PresenceInfo(Requester.clean(cfg.device), "vaulthome", cfg.build.ifBlank { "vaulthome unknown" }, System.currentTimeMillis())
                runCatching { link.announce(info) }.onFailure { link.close(); throw it }
            }
            return link
        }
    }
}
