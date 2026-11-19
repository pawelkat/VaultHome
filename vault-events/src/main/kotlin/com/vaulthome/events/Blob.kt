package com.vaulthome.events

import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque

/**
 * Chunked transfer of files above the inline limit (Rust `blob.rs`).
 *
 * A live event for a file larger than [TransferOptions.inlineMax] carries no `content`, only a [BlobInfo]
 * next to the stamp (whose `sha256` is the whole file's). Bytes are pulled with Zenoh queries on
 * `vault/blob/<sha256>/<index>?cs=<chunk_size>`, answered by the hub (blob store) and by the sender (its
 * [OutgoingCache]). Queries ask for low priority and congestion-control DROP; replies inherit both, so a
 * photo never blocks note events. A dropped reply is just a missing chunk that is asked for again.
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
)

/**
 * Files this device published as blob references, kept in memory so the hub (and other devices) can pull
 * them. Bounded by total bytes and age. Thread-safe (served from a Zenoh callback thread).
 */
class OutgoingCache(private val maxBytes: Long = 64L shl 20, private val ttlMs: Long = 30 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val sha: String, val bytes: ByteArray, val at: Long)

    private val q = ArrayDeque<Entry>()

    @Synchronized
    fun insert(sha: String, bytes: ByteArray) {
        q.removeIf { it.sha == sha }
        q.addLast(Entry(sha, bytes, now()))
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

/** Pulls blobs chunk by chunk through [chunk] (`(sha, index, chunkSize) -> bytes or null`). */
class BlobFetcher(
    private val partialDir: File,
    private val options: TransferOptions,
    private val chunk: (String, Long, Long) -> ByteArray?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Fetch and verify blob [sha] ([info]); throws [BlobFetchException] when it gives up. */
    fun fetch(sha: String, info: BlobInfo): Fetched {
        val t0 = System.currentTimeMillis()
        val cs = info.chunkSize.coerceIn(Blob.MIN_CHUNK_SIZE, Blob.MAX_CHUNK_SIZE)
        val asm = Assembler(partialDir, sha, info.size, cs)
        val resumed = asm.haveCount
        var retries = 0
        var mismatch = false
        var wait = options.backoffMs
        val rounds = options.rounds.coerceAtLeast(1)
        for (round in 0 until rounds) {
            for (i in asm.missing()) {
                val b = chunk(sha, i, cs)
                if (b == null || !asm.put(i, b)) { retries++; break }
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
        if (mismatch && asm.haveCount == 0) throw BlobFetchException("blob ${sha.take(12)}: sha256 mismatch")
        throw BlobFetchException("blob ${sha.take(12)}: incomplete, ${asm.haveCount}/${asm.chunks} chunks")
    }

    /** Fetch the bytes of blob reference [ev]; returns the full event. */
    fun fill(ev: NoteEvent): Pair<NoteEvent, Fetched> {
        val info = ev.blob ?: throw BlobFetchException("not a blob reference")
        val f = fetch(ev.stamp.sha256, info)
        return ev.withContent(f.bytes) to f
    }
}
