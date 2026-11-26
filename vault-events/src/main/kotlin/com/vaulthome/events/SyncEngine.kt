package com.vaulthome.events

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What Settings shows about event sync. */
data class EngineStatus(
    val phase: Phase = Phase.STOPPED,
    val mode: LinkMode? = null,
    val routers: Int = 0,
    val peers: Int = 0,
    val tracked: Int = 0,
    val published: Int = 0,
    val applied: Int = 0,
    val conflicts: Int = 0,
    val lastEvent: String? = null,
    val lastEventAtMs: Long? = null,
    val lastError: String? = null,
    val lastCatchUp: String? = null,
    /** Endpoints actually dialled (local settings merged with the shared vault file). */
    val connect: List<String> = emptyList(),
) {
    enum class Phase { STOPPED, CONNECTING, CONNECTED, ERROR }
}

data class EngineOptions(
    val queryTimeoutMs: Long = 10_000,
    val catchupIntervalMs: Long = 300_000,
    /** No inotify over SAF: the local tree is polled (mtime+size, hash only on change). */
    val rescanIntervalMs: Long = 60_000,
    val linkCheckMs: Long = 5_000,
    val retryMs: Long = 10_000,
    /** Live notes arriving within this window are posted together (one summary when many). */
    val notifyDebounceMs: Long = 2_000,
    /** Inline limit and chunking for large files (see [Blob]). */
    val transfer: TransferOptions = TransferOptions(),
    /** Partial blob downloads (app cache, outside the vault); `null` = a temp dir per device. */
    val partialDir: File? = null,
    /** Wait this long for the hub liveliness token before the first catch-up (then try anyway). `0` = do not wait (tests). */
    val hubWaitMs: Long = 30_000,
    /** Debounce a hub-token reappearance before retriggering catch-up. */
    val catchupDebounceMs: Long = 3_000,
)

private const val ITEM_RETRIES = 3

/**
 * Runs one [SyncCore] against one [EventLink] on a single worker thread, like the Rust agent's
 * event loop: on start catch-up (remote first) then a full scan; then incoming samples, periodic
 * catch-up, periodic rescans and a link check that catches up when the hub link comes back.
 */
class SyncEngine(
    private val core: SyncCore,
    private val config: LinkConfig,
    private val openLink: (LinkConfig) -> EventLink,
    private val options: EngineOptions = EngineOptions(),
    private val onStatus: (EngineStatus) -> Unit = {},
    /** Called on the worker after the vault tree changed (written, deleted or conflict copy). */
    private val onVaultChanged: (List<String>) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Called on the worker before each connect attempt; may extend the connect list (shared vault file). */
    private val resolveConfig: (LinkConfig) -> LinkConfig = { it },
    /** Called on the worker after the first catch-up of a session, before the initial scan publishes local changes. */
    private val onConnected: (SyncCore) -> Unit = {},
    /** "Synced notes" to post: remote changes only, grouped per [SyncNotices.plan]. Called on the worker. */
    private val onNotices: (List<Notice>) -> Unit = {},
) {
    init {
        core.log = log
        core.inlineMax = options.transfer.inlineMax
        core.chunkSize = options.transfer.chunkSize
    }

    private val pendingNotes = ArrayList<SyncedNote>()
    private var notifyScheduled = false
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vault-events").apply { isDaemon = true }
    }
    private val tasks = ArrayList<ScheduledFuture<*>>()
    /** Files this device published as blob references, served to the hub / other devices. */
    private val outgoing = OutgoingCache()
    /** Blob downloads run here so the worker keeps handling notes; results are applied on the worker. */
    private val transfers = Executors.newSingleThreadExecutor { r -> Thread(r, "vault-events-blobs").apply { isDaemon = true } }
    /** `path\nsha` of blob fetches in flight (worker only). */
    private val fetching = HashSet<String>()
    private val partialDir: File = options.partialDir
        ?: File(System.getProperty("java.io.tmpdir"), "vault-events-partial-${core.device.filter { it.isLetterOrDigit() }}")
    private var link: EventLink? = null
    private var linked = 0
    private val hubPresent = AtomicBoolean(false)
    private var sessionReady = false
    private var catchupFailures = 0
    private var retryTask: ScheduledFuture<*>? = null
    private var debounceTask: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    @Volatile var status = EngineStatus(mode = config.mode)
        private set

    private fun update(f: (EngineStatus) -> EngineStatus) {
        status = f(status).copy(tracked = core.state.entries.size)
        onStatus(status)
    }

    fun start() {
        update { it.copy(phase = EngineStatus.Phase.CONNECTING, lastError = null) }
        worker.execute { connect() }
    }

    /** Ask for a rescan now (e.g. after VaultHome itself changed a file). */
    fun pokeLocal(paths: Collection<String> = emptyList()) {
        worker.execute {
            guarded("local change") {
                val b = if (paths.isEmpty()) core.scan() else core.localBatch(paths)
                publishAll(b.publish)
                core.saveIfDirty()
            }
        }
    }

    fun catchUpNow() = worker.execute { guarded("catch-up") { catchUpOrSchedule() } }

    fun stop() {
        if (stopped) return
        stopped = true
        worker.execute {
            tasks.forEach { it.cancel(false) }
            runCatching { core.saveIfDirty() }
            runCatching { link?.close() }
            link = null
            transfers.shutdownNow()
            update { it.copy(phase = EngineStatus.Phase.STOPPED, routers = 0, peers = 0) }
        }
        worker.shutdown()
    }

    fun awaitStopped(ms: Long) = worker.awaitTermination(ms, TimeUnit.MILLISECONDS)

    /** Run [block] on the worker and wait (tests). */
    fun <T> onWorker(block: (SyncCore) -> T): T = worker.submit<T> { block(core) }.get()

    private fun connect() {
        if (stopped) return
        val resolved = try { resolveConfig(config) } catch (e: Throwable) { log("resolve config failed: $e"); config }
        val cfg = if (resolved.device.isBlank()) resolved.copy(device = core.device) else resolved
        update { it.copy(connect = cfg.connect) }
        val l = try {
            openLink(cfg)
        } catch (e: Throwable) {
            log("cannot open zenoh session ${cfg.mode.wire} ${cfg.connect}: ${e.message}; retrying")
            update { it.copy(phase = EngineStatus.Phase.CONNECTING, lastError = "connect: ${e.message ?: e.javaClass.simpleName}") }
            if (!stopped) worker.schedule({ connect() }, options.retryMs, TimeUnit.MILLISECONDS)
            return
        }
        link = l
        try {
            l.subscribe { key, bytes -> if (!stopped) worker.execute { onSample(key, bytes) } }
        } catch (e: Throwable) {
            update { it.copy(phase = EngineStatus.Phase.ERROR, lastError = "subscribe: ${e.message}") }
            return
        }
        try {
            l.serveBlobs(core.device, { sha, i, cs -> outgoing.chunk(sha, i, cs) }, { outgoing.label(it) }, log)
        } catch (e: Throwable) {
            log("serving blobs failed: $e")
        }
        try {
            l.subscribeHubPresence { present ->
                val was = hubPresent.getAndSet(present)
                if (present && !was) log("hub online")
                else if (!present && was) log("hub offline")
                if (present && sessionReady && !stopped) worker.execute { scheduleDebouncedCatchup() }
            }
        } catch (e: Throwable) {
            log("hub presence subscribe failed: $e")
        }
        if (options.hubWaitMs > 0 && !hubPresent.get()) {
            val t0 = System.currentTimeMillis()
            while (!stopped && !hubPresent.get() && System.currentTimeMillis() - t0 < options.hubWaitMs) {
                Thread.sleep(50)
            }
            if (!hubPresent.get()) log("no hub token after ${options.hubWaitMs / 1000} s; catching up anyway")
        }
        linked = l.routerCount() + l.peerCount()
        update { it.copy(phase = EngineStatus.Phase.CONNECTED, routers = l.routerCount(), peers = l.peerCount(), lastError = null) }
        // Remote first, then local: incoming changes are applied before offline local edits go out.
        guarded("catch-up") { catchUpOrSchedule() }
        sessionReady = true
        guarded("shared config") { onConnected(core) }
        guarded("initial scan") {
            val b = core.scan()
            core.seeding = false
            publishAll(b.publish)
            core.saveIfDirty()
            log("initial scan: published ${b.publish.size}")
            core.lastScanRefused?.let { r -> update { it.copy(lastError = r) } }
        }
        tasks += worker.scheduleWithFixedDelay({ guarded("catch-up") { catchUpOrSchedule() } }, options.catchupIntervalMs, options.catchupIntervalMs, TimeUnit.MILLISECONDS)
        tasks += worker.scheduleWithFixedDelay({
            guarded("rescan") {
                val b = core.scan()
                publishAll(b.publish)
                if (b.refusedDeletes.isNotEmpty()) {
                    update { it.copy(lastError = "mass-delete guard: ${b.refusedDeletes.size} notes vanished at once; deletes not published") }
                }
                core.lastScanRefused?.let { r -> update { it.copy(lastError = r) } }
                core.saveIfDirty()
            }
        }, options.rescanIntervalMs, options.rescanIntervalMs, TimeUnit.MILLISECONDS)
        tasks += worker.scheduleWithFixedDelay({ guarded("link check") { linkCheck() } }, options.linkCheckMs, options.linkCheckMs, TimeUnit.MILLISECONDS)
    }

    private fun linkCheck() {
        val l = link ?: return
        val r = l.routerCount()
        val p = l.peerCount()
        val n = r + p
        if (n > 0 && linked == 0) {
            log("hub link up; catching up")
            catchUpOrSchedule()
        }
        linked = n
        if (r != status.routers || p != status.peers) update { it.copy(routers = r, peers = p) }
    }

    private fun onSample(key: String, bytes: ByteArray) {
        val ev = try {
            NoteEvent.decode(bytes, key)
        } catch (e: DecodeException) {
            log("dropping bad event on $key: ${e.message}")
            update { it.copy(lastError = "bad event on $key: ${e.kind}") }
            return
        }
        if (ev.isBlobRef && runCatching { core.needsBytes(ev) }.getOrDefault(true)) {
            fetchThenApply(ev)
            return
        }
        applyIncoming(ev)
    }

    private fun fetcher(l: EventLink) = BlobFetcher(partialDir, options.transfer, { sha, i, cs, src -> l.fetchChunk(sha, i, cs, options.transfer.chunkTimeoutMs, src) })

    /** Pull the bytes of blob reference [ev] off the worker, then apply the verified event on the worker. */
    private fun fetchThenApply(ev: NoteEvent) {
        val l = link ?: return
        val id = "${ev.path}\n${ev.stamp.sha256}"
        if (!fetching.add(id)) return
        val b = ev.blob!!
        log("fetching blob ${ev.stamp.sha256.take(12)} for ${ev.path} v${ev.stamp.version} from ${ev.stamp.origin}: ${b.size} B in ${b.chunks} chunks")
        try {
            transfers.execute {
                waitForHub(l, ev)
                val t0 = System.currentTimeMillis()
                val full = try {
                    val (full, f) = fetcher(l).fill(ev)
                    log(fetchedLine(ev, f))
                    full
                } catch (e: Throwable) {
                    log("blob for ${ev.path} not fetched: ${e.message}; catch-up retries")
                    publishFailAck(l, ev, t0, e.message ?: e.javaClass.simpleName)
                    null
                }
                if (!stopped) worker.execute {
                    fetching.remove(id)
                    if (full != null) applyIncoming(full, t0)
                }
            }
        } catch (e: Throwable) {
            fetching.remove(id)
        }
    }

    /** Wait (up to [TransferOptions.hubWaitMs]) until the hub stores live reference [ev] (Rust `wait_for_hub`). */
    private fun waitForHub(l: EventLink, ev: NoteEvent): Boolean {
        val t0 = System.currentTimeMillis()
        while (!stopped) {
            val stored = runCatching { l.fetchNote(ev.path, 3_000) }.getOrNull()
            if (stored != null && (stored.stamp.sha256 == ev.stamp.sha256 || stored.stamp.beats(ev.stamp))) {
                val ms = System.currentTimeMillis() - t0
                if (ms > 1_000) log("hub has ${ev.path} after $ms ms; fetching from the hub")
                return true
            }
            if (System.currentTimeMillis() - t0 >= options.transfer.hubWaitMs) {
                log("hub has not stored ${ev.path} after ${options.transfer.hubWaitMs / 1000} s; fetching from ${ev.stamp.origin} too")
                return false
            }
            Thread.sleep(1_000)
        }
        return false
    }

    private fun applyIncoming(ev: NoteEvent, startedAtMs: Long = System.currentTimeMillis(), contentFile: File? = null) {
        guarded("apply ${ev.path}") {
            val ancestors = historyAncestors(ev.path, ev.stamp.sha256)
            val a = core.onIncoming(ev, ancestors, contentFile)
            if (a.changed) log(appliedLine(ev))
            if (a.changed || a.conflicts.isNotEmpty()) {
                update {
                    it.copy(
                        applied = it.applied + if (a.changed) 1 else 0,
                        conflicts = it.conflicts + a.conflicts.size,
                        lastEvent = "${if (ev.stamp.deleted) "deleted" else "received"} ${ev.path} v${ev.stamp.version} from ${ev.stamp.origin}",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
                onVaultChanged(listOf(ev.path) + a.conflicts)
                queueNotes(SyncNotices.fromIncoming(ev, a, core.device))
            }
            publishIncomingAck(ev, a, startedAtMs)
            publishAll(a.publish)
            core.saveIfDirty()
        }
    }

    /** One catch-up pass against the hub (Rust `agent::catch_up`). Returns `false` if no hub answered. */
    private fun catchupBackoff(failures: Int, normalMs: Long): Long {
        val steps = longArrayOf(2_000, 5_000, 10_000, 30_000)
        if (failures <= 0) return normalMs
        return steps.getOrNull(failures - 1) ?: normalMs
    }

    private fun scheduleDebouncedCatchup() {
        debounceTask?.cancel(false)
        val delay = options.catchupDebounceMs
        if (delay <= 0) {
            guarded("catch-up") { catchUpOrSchedule() }
            return
        }
        debounceTask = worker.schedule({ guarded("catch-up") { catchUpOrSchedule() } }, delay, TimeUnit.MILLISECONDS)
    }

    private fun catchUpOrSchedule() {
        val t0 = System.currentTimeMillis()
        val ok = try {
            catchUp()
        } catch (e: Throwable) {
            log("catch-up failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
        if (ok) {
            catchupFailures = 0
            retryTask?.cancel(false)
            retryTask = null
            return
        }
        catchupFailures++
        val wait = catchupBackoff(catchupFailures, options.catchupIntervalMs)
        val elapsed = System.currentTimeMillis() - t0
        val noRoute = elapsed < 500
        log(
            "catch-up: no hub answered" + (if (noRoute) " (no route)" else "") +
                "; retry in ${wait / 1000}s",
        )
        retryTask?.cancel(false)
        retryTask = worker.schedule({ guarded("catch-up") { catchUpOrSchedule() } }, wait, TimeUnit.MILLISECONDS)
    }

    private fun fetchNoteRetry(l: EventLink, path: String): NoteEvent? {
        repeat(ITEM_RETRIES) { i ->
            val ev = runCatching { l.fetchNote(path, options.queryTimeoutMs) }.getOrNull()
            if (ev != null) return ev
            if (i < ITEM_RETRIES - 1) Thread.sleep(400)
        }
        return null
    }

    private fun historyAncestors(path: String, incomingSha: String): List<String> {
        val l = link ?: return emptyList()
        val h = runCatching { l.fetchHistory(path, 50, options.queryTimeoutMs) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (e in h.entries) {
            if (e.sha256.isNotEmpty() && e.sha256 != incomingSha) out += e.sha256
            val prev = e.prevSha256
            if (!prev.isNullOrEmpty() && prev != incomingSha) out += prev
        }
        return out.toList()
    }

    private fun catchUp(): Boolean {
        val l = link ?: return false
        val tStart = System.currentTimeMillis()
        val index = l.fetchIndex(options.queryTimeoutMs)
        if (index == null) {
            update { it.copy(lastCatchUp = "no hub answered the catch-up query") }
            return false
        }
        val (fetch, republish) = core.planCatchup(index.stamps)
        val byPath = index.stamps.associateBy { it.path }
        var applied = 0
        var conflicts = 0
        var adopted = 0
        var fetched = 0
        var failed = 0
        val changed = ArrayList<String>()
        val notes = ArrayList<SyncedNote>()
        for (p in fetch) {
            val t0 = System.currentTimeMillis()
            val hubStamp = byPath[p]
            if (hubStamp != null && core.adoptIfSame(hubStamp)) {
                adopted++
                if (!hubStamp.deleted && hubStamp.sha256.isNotEmpty()) {
                    val bytes = (core.fs.inspect(p) as? Inspect.File)?.size ?: 0L
                    publishAck(l, hubStamp.sha256, TransferAck.ok(p, bytes, System.currentTimeMillis() - t0))
                }
                continue
            }
            var ev = fetchNoteRetry(l, p)
            if (ev == null) { log("catch-up: hub listed $p but returned nothing"); failed++; continue }
            val ancestors = historyAncestors(p, ev.stamp.sha256)
            var contentFile: File? = null
            if (ev.isBlobRef && core.needsBytes(ev, ancestors)) {
                val got = try {
                    val (full, f) = fetcher(l).fill(ev)
                    log("catch-up: ${fetchedLine(ev, f)}")
                    fetched++
                    full
                } catch (e: Throwable) {
                    var last: Throwable = e
                    var full: NoteEvent? = null
                    for (i in 2..ITEM_RETRIES) {
                        try {
                            val (f2, fetchedInfo) = fetcher(l).fill(ev)
                            log("catch-up: ${fetchedLine(ev, fetchedInfo)}")
                            fetched++
                            full = f2
                            last = e
                            break
                        } catch (e2: Throwable) {
                            last = e2
                            Thread.sleep(400)
                        }
                    }
                    if (full == null) {
                        log("catch-up: blob for $p not fetched: ${last.message}; next pass retries")
                        publishFailAck(l, ev, t0, last.message ?: last.javaClass.simpleName)
                        failed++
                        continue
                    } else full
                }
                ev = got
            } else if (!ev.isBlobRef) {
                fetched++
            }
            val a = core.onIncoming(ev, ancestors, contentFile)
            if (a.changed) log("catch-up: ${appliedLine(ev)}")
            if (a.changed) { applied++; changed += p }
            notes += SyncNotices.fromIncoming(ev, a, core.device)
            conflicts += a.conflicts.size
            changed += a.conflicts
            publishIncomingAck(ev, a, t0)
            publishAll(a.publish)
        }
        val out = republish.mapNotNull { core.republish(it) }
        publishAll(out)
        core.saveIfDirty()
        val elapsed = (System.currentTimeMillis() - tStart) / 1000.0
        val summary = "catch-up: ${index.stamps.size} listed, $fetched fetched, $failed failed in ${"%.1f".format(java.util.Locale.ROOT, elapsed)}s"
        val msg = "hub ${index.hub}: ${index.stamps.size} stored, fetched ${fetch.size - adopted}, adopted $adopted, applied $applied, conflicts $conflicts, republished ${out.size}"
        log("$summary ($msg)")
        update { it.copy(lastCatchUp = msg, applied = it.applied + applied, conflicts = it.conflicts + conflicts) }
        if (changed.isNotEmpty()) onVaultChanged(changed)
        postNotices(SyncNotices.plan(notes, catchUp = true))
        return true
    }

    private fun queueNotes(notes: List<SyncedNote>) {
        if (notes.isEmpty() || stopped) return
        pendingNotes += notes
        if (notifyScheduled) return
        notifyScheduled = true
        worker.schedule({
            notifyScheduled = false
            val batch = pendingNotes.toList()
            pendingNotes.clear()
            postNotices(SyncNotices.plan(batch, catchUp = false))
        }, options.notifyDebounceMs, TimeUnit.MILLISECONDS)
    }

    private fun postNotices(n: List<Notice>) {
        if (n.isEmpty() || stopped) return
        try { onNotices(n) } catch (e: Throwable) { log("notify failed: $e") }
    }

    private fun publishAll(evs: List<NoteEvent>) {
        val l = link ?: return
        for (ev in evs) {
            try {
                val t = options.transfer
                val ref = ev.toBlobRef(t.inlineMax, t.chunkSize)
                val toSend = ref ?: ev
                if (toSend.isBlobRef) {
                    val f = core.fs.localFile(ev.path)
                    if (f != null && f.isFile) outgoing.insertFile(ev.stamp.sha256, f, ev.path)
                    else ev.content?.let { outgoing.insert(ev.stamp.sha256, it, ev.path) }
                }
                val t0 = System.currentTimeMillis()
                l.publish(toSend)
                val b = toSend.blob
                log(publishedLine(ev) + if (b != null) " as blob (${b.size} B, ${b.chunks} chunks of ${b.chunkSize} B, sha256 ${ev.stamp.sha256.take(12)})" else "")
                if (!ev.stamp.deleted && ev.stamp.sha256.isNotEmpty()) {
                    publishAck(l, ev.stamp.sha256, TransferAck.ok(ev.path, ev.size, System.currentTimeMillis() - t0))
                }
                update {
                    it.copy(
                        published = it.published + 1,
                        lastEvent = "sent ${ev.path} v${ev.stamp.version}${if (ev.stamp.deleted) " (delete)" else ""}",
                        lastEventAtMs = System.currentTimeMillis(),
                    )
                }
            } catch (e: Throwable) {
                log("publish ${ev.path} failed: ${e.message}")
                update { it.copy(lastError = "publish ${ev.path}: ${e.message}") }
            }
        }
    }

    private fun publishIncomingAck(ev: NoteEvent, a: Applied, startedAtMs: Long) {
        if (ev.stamp.deleted || ev.isBlobRef || ev.stamp.sha256.isEmpty()) return
        if (a.decision !is Decision.Apply && a.decision !is Decision.Adopt) return
        val l = link ?: return
        publishAck(l, ev.stamp.sha256, TransferAck.ok(ev.path, ev.size, System.currentTimeMillis() - startedAtMs))
    }

    private fun publishFailAck(l: EventLink, ev: NoteEvent, startedAtMs: Long, reason: String) {
        if (ev.stamp.sha256.isEmpty()) return
        publishAck(l, ev.stamp.sha256, TransferAck.fail(ev.path, ev.size, System.currentTimeMillis() - startedAtMs, reason))
    }

    private fun publishAck(l: EventLink, sha256: String, ack: TransferAck) {
        try {
            l.publishAck(sha256, ack)
        } catch (e: Throwable) {
            log("ack ${ack.path} failed: ${e.message}")
        }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log("$what failed: $e")
            update { it.copy(lastError = "$what: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
}

/** `fetched blob <sha12> for <path>: N B, M chunks in T ms (...), sha256 ok` (same as the Rust agent). */
fun fetchedLine(ev: NoteEvent, f: Fetched): String =
    "fetched blob ${ev.stamp.sha256.take(12)} for ${ev.path}: ${f.bytes.size} B, ${f.chunks} chunks in ${f.elapsedMs} ms (${f.resumed} resumed, ${f.retries} retried), sha256 ok"

/** `published <path> vN by <writer> (<cause>)[ (delete)]` (same as the Rust agent). */
fun publishedLine(ev: NoteEvent): String =
    "published ${ev.path} v${ev.stamp.version} by ${ev.stamp.writerStr} (${ev.stamp.causeStr})${if (ev.stamp.deleted) " (delete)" else ""}"

/** `applied <path> vN from <origin> by <writer> (<cause>)[ (delete)]`. */
fun appliedLine(ev: NoteEvent): String =
    "applied ${ev.path} v${ev.stamp.version} from ${ev.stamp.origin} by ${ev.stamp.writerStr} (${ev.stamp.causeStr})${if (ev.stamp.deleted) " (delete)" else ""}"
