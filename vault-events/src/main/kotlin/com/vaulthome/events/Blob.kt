package com.vaulthome.events

import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chunked transfer of files above the inline limit (Rust `blob.rs`).
 *
 * A live event for a file larger than [TransferOptions.inlineMax] carries no `content`, only a [BlobInfo]
 * next to the stamp (whose `sha256` is the whole file's). Bytes are pulled with Zenoh queries on
 * `vault/blob/<sha256>/<index>?cs=<chunk_size>;src=<who>`, answered by the hub (blob store) and by the sender
 * (its [OutgoingCache]). `src` names one server: receivers ask `hub`, then the publishing device, then `any`,
 * and keep the first that answered, so each chunk crosses a slow router (note4c) once. Queries without `src`
 * (older receivers) are answered by everyone. Queries ask for priority DATA_LOW and congestion-control BLOCK
 * (replies inherit both): note events still go first, and a 256 KiB reply crossing a router waits for room
 * instead of being dropped. [TransferOptions.inFlight] chunks are requested at once; a receiver takes the
 * first reply and does not wait for the query's final message.
 *
 * Receivers assemble into `<partialDir>/<sha256>.part` (app cache, outside the vault: SAF providers,
 * Obsidian and Autosync never see a partial file), verify the SHA-256, and only then hand the bytes to
 * [SyncCore.onIncoming], which writes via [VaultTree.writeAtomic]. A mismatch discards the partial file and
 * starts over.
 */
object Blob {
    const val PREFIX = "vault/blob"
    const val WILDCARD = "vault/blob/**"
    const val DEFAULT_INLINE_MAX: Long = 1L shl 20
    const val DEFAULT_CHUNK_SIZE: Long = 256L shl 10
    const val MIN_CHUNK_SIZE: Long = 4L shl 10
    const val MAX_CHUNK_SIZE: Long = 4L shl 20

    /** `ceil(size / chunkSize)`; 0 for an empty file. */
    fun chunkCount(size: Long, chunkSize: Long): Long = if (chunkSize <= 0) 0 else (size + chunkSize - 1) / chunkSize

    /** `(offset, len)` of chunk [i], `null` past the end. */
    fun chunkRange(size: Long, chunkSize: Long, i: Long): Pair<Long, Long>? {
        if (i < 0 || chunkSize <= 0) return null
        val start = i * chunkSize
        if (start >= size || start / chunkSize != i) return null
        return start to minOf(chunkSize, size - start)
    }

    fun key(sha: String, i: Long): String = "$PREFIX/$sha/$i"

    fun selector(sha: String, i: Long, chunkSize: Long): String = "${key(sha, i)}?cs=$chunkSize"

    /** `src` asking only the hub / every holder. */
    const val SRC_HUB = "hub"
    const val SRC_ANY = "any"

    /** `vault/blob/<sha256>/<i>?cs=<chunk_size>;src=<who>` */
    fun selector(sha: String, i: Long, chunkSize: Long, src: String): String = "${selector(sha, i, chunkSize)};src=$src"

    /** Does the agent [device] answer a query with [src]? No `src` (older receivers) or `any`: yes. */
    fun agentAnswers(device: String, src: String?): Boolean = src == null || src == SRC_ANY || src == device

    /** Order a receiver asks in for a file published by [origin]: hub, then [origin], then anyone. */
    fun sources(origin: String): List<String> = listOf(SRC_HUB, origin, SRC_ANY).filter { it.isNotEmpty() }.distinct()

    /** Inverse of [key]: `(sha256, index)`. */
    fun parseKey(k: String): Pair<String, Long>? {
        val rest = k.removePrefix("$PREFIX/").takeIf { it != k } ?: return null
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val sha = rest.substring(0, slash)
        val idx = rest.substring(slash + 1)
        if (!isSha256Hex(sha) || idx.isEmpty() || !idx.all { it in '0'..'9' }) return null
        return sha to (idx.toLongOrNull() ?: return null)
    }

    /** The `cs` selector parameter, clamped; default [DEFAULT_CHUNK_SIZE]. */
    fun chunkSizeParam(v: String?): Long = (v?.toLongOrNull() ?: DEFAULT_CHUNK_SIZE).coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)

    /** Slice chunk [i] of [bytes]. */
    fun slice(bytes: ByteArray, i: Long, chunkSize: Long): ByteArray? {
        val (off, len) = chunkRange(bytes.size.toLong(), chunkSize, i) ?: return null
        return bytes.copyOfRange(off.toInt(), (off + len).toInt())
    }
}

/** Transfer tuning (Rust `blob::TransferOpts`). */
data class TransferOptions(
    val inlineMax: Long = Blob.DEFAULT_INLINE_MAX,
    val chunkSize: Long = Blob.DEFAULT_CHUNK_SIZE,
    val chunkTimeoutMs: Long = 15_000,
    /** Passes over the missing chunks before giving up (catch-up retries later). */
    val rounds: Int = 8,
    /** Pause between passes (doubles, max 8x). */
    val backoffMs: Long = 500,
    /** Chunk queries in flight per transfer (1 MiB at the default chunk size). */
    val inFlight: Int = 4,
    /**
     * A live blob reference: how long to wait for the hub to store the file before also asking the sending
     * device. One copy then crosses a slow router hop instead of one per receiver.
     */
    val hubWaitMs: Long = 30_000,
)

/**
 * Files this device published as blob references, kept in memory so the hub (and other devices) can pull
 * them. Bounded by total bytes and age. Thread-safe (served from a Zenoh callback thread).
 */
class OutgoingCache(private val maxBytes: Long = 64L shl 20, private val ttlMs: Long = 30 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val sha: String, val bytes: ByteArray, val at: Long, val path: String?)

    private val q = ArrayDeque<Entry>()

    @Synchronized
    fun insert(sha: String, bytes: ByteArray, path: String? = null) {
        q.removeIf { it.sha == sha }
        q.addLast(Entry(sha, bytes, now(), path))
        var total = q.sumOf { it.bytes.size.toLong() }
        while (total > maxBytes && q.size > 1) total -= q.removeFirst().bytes.size
    }

    @Synchronized
    fun get(sha: String): ByteArray? {
        val t = now()
        q.removeIf { t - it.at >= ttlMs }
        return q.firstOrNull { it.sha == sha }?.bytes
    }

    fun chunk(sha: String, i: Long, chunkSize: Long): ByteArray? = get(sha)?.let { Blob.slice(it, i, chunkSize) }

    /** Size and vault path of a cached blob (for the served-blob summary line). */
    @Synchronized
    fun label(sha: String): BlobLabel? = q.firstOrNull { it.sha == sha }?.let { BlobLabel(it.bytes.size.toLong(), it.path) }

    @get:Synchronized val size: Int get() = q.size
}

/**
 * Reassembles one blob in `<dir>/<sha>.part`, remembering finished chunks in `<dir>/<sha>.have` so a retry or
 * an app restart asks only for the chunks still missing (same file layout as the Rust agent).
 */
class Assembler(dir: File, val sha: String, val size: Long, val chunkSize: Long) {
    private val part = File(dir, "$sha.part")
    private val haveFile = File(dir, "$sha.have")
    private val have = sortedSetOf<Long>()
    private val header = "$sha $size $chunkSize"

    val chunks: Long get() = Blob.chunkCount(size, chunkSize)
    val haveCount: Int get() = have.size

    init {
        require(isSha256Hex(sha) && chunkSize > 0) { "bad blob $sha / chunk size $chunkSize" }
        dir.mkdirs()
        val lines = runCatching { haveFile.readLines() }.getOrNull()
        if (lines != null && lines.firstOrNull() == header && part.isFile && part.length() == size) {
            lines.drop(1).mapNotNull { it.trim().toLongOrNull() }.filter { it in 0 until chunks }.forEach { have += it }
        } else {
            reset()
        }
    }

    fun missing(): List<Long> = (0 until chunks).filter { it !in have }

    /** Store chunk [i]; `false` on a wrong length. */
    fun put(i: Long, bytes: ByteArray): Boolean {
        val (off, len) = Blob.chunkRange(size, chunkSize, i) ?: return false
        if (bytes.size.toLong() != len) return false
        RandomAccessFile(part, "rw").use { it.seek(off); it.write(bytes) }
        haveFile.appendText("$i\n")
        have += i
        return true
    }

    /** All chunks there: the verified bytes (partial files removed), or `null` on a mismatch (starts over). */
    fun finish(): ByteArray? {
        check(missing().isEmpty()) { "finish() with chunks missing" }
        val bytes = part.readBytes()
        if (bytes.size.toLong() != size || sha256Hex(bytes) != sha) {
            reset()
            return null
        }
        discard()
        return bytes
    }

    fun reset() {
        RandomAccessFile(part, "rw").use { it.setLength(0); it.setLength(size) }
        haveFile.writeText("$header\n")
        have.clear()
    }

    fun discard() {
        part.delete()
        haveFile.delete()
    }
}

class BlobFetchException(message: String) : Exception(message)

/** Outcome of a successful [BlobFetcher.fetch]. */
data class Fetched(val bytes: ByteArray, val chunks: Long, val resumed: Int, val retries: Int, val elapsedMs: Long)

/**
 * Pulls blobs chunk by chunk through [chunk] `(sha, index, chunkSize, src) -> bytes or null`, with
 * [TransferOptions.inFlight] requests at once. Tries the `src` values in order and sticks to the first that
 * answered.
 */
class BlobFetcher(
    private val partialDir: File,
    private val options: TransferOptions,
    private val chunk: (String, Long, Long, String) -> ByteArray?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Fetch and verify blob [sha] ([info]) from [sources]; throws [BlobFetchException] when it gives up. */
    fun fetch(sha: String, info: BlobInfo, sources: List<String> = listOf(Blob.SRC_ANY)): Fetched {
        val t0 = System.currentTimeMillis()
        val cs = info.chunkSize.coerceIn(Blob.MIN_CHUNK_SIZE, Blob.MAX_CHUNK_SIZE)
        val asm = Assembler(partialDir, sha, info.size, cs)
        val resumed = asm.haveCount
        var retries = 0
        var mismatch = false
        var wait = options.backoffMs
        val rounds = options.rounds.coerceAtLeast(1)
        val width = options.inFlight.coerceIn(1, 16)
        val srcs = sources.ifEmpty { listOf(Blob.SRC_ANY) }
        val pref = AtomicInteger(0)
        fun one(i: Long): ByteArray? {
            val start = pref.get()
            for (k in srcs.indices) {
                val idx = (start + k) % srcs.size
                val b = runCatching { chunk(sha, i, cs, srcs[idx]) }.getOrNull()
                if (b != null && b.isNotEmpty()) { pref.set(idx); return b }
            }
            return null
        }
        val pool = Executors.newFixedThreadPool(width) { r -> Thread(r, "vault-events-chunk").apply { isDaemon = true } }
        try {
            for (round in 0 until rounds) {
                // Up to `width` requests in flight; after `width` failures in a row the source is gone for now.
                val ecs = ExecutorCompletionService<Pair<Long, ByteArray?>>(pool)
                val todo = asm.missing().iterator()
                var running = 0
                var failedInARow = 0
                while (true) {
                    while (running < width && failedInARow < width && todo.hasNext()) {
                        val i = todo.next()
                        ecs.submit { i to one(i) }
                        running++
                    }
                    if (running == 0) break
                    val (i, b) = ecs.take().get()
                    running--
                    if (b != null && asm.put(i, b)) failedInARow = 0 else { retries++; failedInARow++ }
                }
                if (asm.missing().isEmpty()) {
                    val bytes = asm.finish()
                    if (bytes != null) return Fetched(bytes, asm.chunks, resumed, retries, System.currentTimeMillis() - t0)
                    mismatch = true
                    continue
                }
                if (round + 1 < rounds) {
                    sleep(wait)
                    wait = minOf(wait * 2, options.backoffMs * 8)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (mismatch && asm.haveCount == 0) throw BlobFetchException("blob ${sha.take(12)}: sha256 mismatch")
        throw BlobFetchException("blob ${sha.take(12)}: incomplete, ${asm.haveCount}/${asm.chunks} chunks")
    }

    /** Fetch the bytes of blob reference [ev] (hub first, then its origin device); returns the full event. */
    fun fill(ev: NoteEvent): Pair<NoteEvent, Fetched> {
        val info = ev.blob ?: throw BlobFetchException("not a blob reference")
        val f = fetch(ev.stamp.sha256, info, Blob.sources(ev.stamp.origin))
        return ev.withContent(f.bytes) to f
    }
}
