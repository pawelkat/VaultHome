package com.vaulthome.events

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.handlers.Callback
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.pubsub.Subscriber
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.QoS
import io.zenoh.query.ConsolidationMode
import io.zenoh.query.QueryTarget
import io.zenoh.query.Reply
import io.zenoh.query.Selector
import io.zenoh.sample.Sample
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [EventLink] over zenoh-kotlin 1.10.2 (same Zenoh release as the box hub). Mirrors Rust `net.rs`:
 * puts block under congestion, catch-up queries use target ALL + consolidation NONE.
 */
class ZenohLink private constructor(private val session: Session) : EventLink {
    private var subscriber: Subscriber<Unit>? = null

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
        val sel = Selector(KeyExpr.tryFrom(key).getOrThrow())
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

    override fun routerCount(): Int = session.info().routersZid().getOrNull()?.size ?: 0

    override fun peerCount(): Int = session.info().peersZid().getOrNull()?.size ?: 0

    override fun close() {
        runCatching { subscriber?.close() }
        subscriber = null
        runCatching { session.close() }
    }

    companion object {
        /** Open a session (client mode fails when no router is reachable, like the Rust agent). */
        fun open(cfg: LinkConfig): ZenohLink {
            val c = Config.default()
            for ((k, v) in cfg.inserts()) c.insertJson5(k, v).getOrThrow()
            return ZenohLink(Zenoh.open(c).getOrThrow())
        }
    }
}
