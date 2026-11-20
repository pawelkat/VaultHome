package com.vaulthome.events

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What is on disk at a vault path (Rust `vault::Disk`). */
sealed class Disk {
    data object Absent : Disk()

    /** Present but not synced (too large, symlink, directory, ignored, ...). */
    data class Skipped(val why: String) : Disk()

    class Present(val sha256: String, val bytes: ByteArray, val mtimeMs: Long, val size: Long) : Disk()

    val sha: String? get() = (this as? Present)?.sha256
}

/**
 * Filesystem side of the sync core. VaultHome implements it over its SAF tree grant; tests and the
 * box use [FileVaultTree]. Paths are validated vault-relative `/` paths.
 */
/** SHA-256 and metadata without holding the file bytes (Rust `vault::Inspect`). */
sealed class Inspect {
    data object Absent : Inspect()
    data class Skipped(val why: String) : Inspect()
    data class File(val sha256: String, val mtimeMs: Long, val size: Long) : Inspect()
    val sha: String? get() = (this as? File)?.sha256
}

interface VaultTree {
    /** Root is reachable (mounted / grant still valid). */
    fun rootExists(): Boolean

    /** `(mtimeMs, size)` of a regular file without hashing, or `null`. */
    fun stat(rel: String): Pair<Long, Long>?

    /** Must return [Disk.Skipped] for [VaultRules.isIgnored] paths, non-files and files over the size cap. */
    fun read(rel: String): Disk

    /** Write so readers never see a half-written note (temp file + rename where the backend allows). */
    fun writeAtomic(rel: String, bytes: ByteArray)

    /** Remove a regular file; missing is fine. */
    fun remove(rel: String)

    /** Every syncable regular file (relative, sorted); skips ignored paths and does not follow symlinks. */
    fun scan(): List<String>

    /** Hash the file without loading it; default falls back to [read]. */
    fun inspect(rel: String): Inspect = when (val d = read(rel)) {
        is Disk.Absent -> Inspect.Absent
        is Disk.Skipped -> Inspect.Skipped(d.why)
        is Disk.Present -> Inspect.File(d.sha256, d.mtimeMs, d.size)
    }

    /** A [length]-byte range starting at [offset], or `null` if the file is missing or too small. */
    fun readRange(rel: String, offset: Long, length: Int): ByteArray? {
        val d = read(rel) as? Disk.Present ?: return null
        if (offset < 0 || offset + length > d.bytes.size) return null
        return d.bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    /** Copy [src] onto [rel] atomically when the backend can; default loads the file. */
    fun writeAtomicFrom(rel: String, src: java.io.File) = writeAtomic(rel, src.readBytes())

    /** Absolute file for a vault path when this tree is a local directory; else `null`. */
    fun localFile(rel: String): java.io.File? = null
}

/**
 * Where to store a conflict copy of [bytes]: [base] if free, else ` 2`, ` 3`, ...; `null` when a
 * candidate already holds exactly these bytes (copy already made, possibly by another device).
 */
fun VaultTree.conflictSlot(base: String, bytes: ByteArray): String? = conflictSlotSha(base, sha256Hex(bytes))

/** [conflictSlot] by SHA-256, so a large loser is not loaded just to pick a name. */
fun VaultTree.conflictSlotSha(base: String, want: String): String? {
    for (n in 1 until 1000) {
        val cand = if (n == 1) base else VaultRules.numbered(base, n)
        when (val d = inspect(cand)) {
            is Inspect.Absent -> return cand
            is Inspect.File -> if (d.sha256 == want) return null
            else -> {}
        }
    }
    throw IOException("no free conflict name for $base")
}

/** [VaultTree] on a plain directory (JVM tests, box tools). Same semantics as Rust `VaultFs`. */
class FileVaultTree(val root: File, private val maxBytes: Long = VaultRules.DEFAULT_MAX_BYTES) : VaultTree {
    private fun abs(rel: String): File = File(root, VaultKey.validateRel(rel))

    override fun rootExists() = root.isDirectory

    override fun stat(rel: String): Pair<Long, Long>? {
        val f = runCatching { abs(rel) }.getOrNull() ?: return null
        val p = f.toPath()
        if (java.nio.file.Files.isSymbolicLink(p) || !f.isFile) return null
        return f.lastModified() to f.length()
    }

    override fun read(rel: String): Disk {
        if (VaultRules.isIgnored(rel)) return Disk.Skipped("ignored path")
        val f = abs(rel)
        val p = f.toPath()
        if (java.nio.file.Files.isSymbolicLink(p)) return Disk.Skipped("symlink")
        if (!f.exists()) return Disk.Absent
        if (!f.isFile) return Disk.Skipped("not a regular file")
        if (f.length() > maxBytes) return Disk.Skipped("${f.length()} bytes > max $maxBytes")
        val mtime = f.lastModified()
        val bytes = try { f.readBytes() } catch (_: java.io.FileNotFoundException) { return Disk.Absent }
        return Disk.Present(sha256Hex(bytes), bytes, mtime, bytes.size.toLong())
    }

    override fun writeAtomic(rel: String, bytes: ByteArray) {
        val f = abs(rel)
        if (f.exists() && !f.isFile) throw IOException("refusing to replace non-file $f")
        val dir = f.parentFile ?: root
        dir.mkdirs()
        val tmp = File(dir, "${VaultRules.TMP_PREFIX}${System.nanoTime()}-${SEQ.getAndIncrement()}")
        try {
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    override fun remove(rel: String) {
        val f = abs(rel)
        if (!f.exists()) return
        if (!f.isFile) throw IOException("refusing to delete non-file $f")
        if (!f.delete() && f.exists()) throw IOException("cannot delete $f")
    }

    override fun inspect(rel: String): Inspect {
        if (VaultRules.isIgnored(rel)) return Inspect.Skipped("ignored path")
        val f = abs(rel)
        val p = f.toPath()
        if (java.nio.file.Files.isSymbolicLink(p)) return Inspect.Skipped("symlink")
        if (!f.exists()) return Inspect.Absent
        if (!f.isFile) return Inspect.Skipped("not a regular file")
        if (f.length() > maxBytes) return Inspect.Skipped("${f.length()} bytes > max $maxBytes")
        val mtime = f.lastModified()
        val sha = try { sha256File(f) } catch (_: java.io.FileNotFoundException) { return Inspect.Absent }
        return Inspect.File(sha, mtime, f.length())
    }

    override fun readRange(rel: String, offset: Long, length: Int): ByteArray? {
        val f = abs(rel)
        if (!f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())) return null
        if (offset < 0 || length < 0 || offset + length > f.length()) return null
        return java.io.RandomAccessFile(f, "r").use { it.seek(offset); val b = ByteArray(length); it.readFully(b); b }
    }

    override fun writeAtomicFrom(rel: String, src: java.io.File) {
        val f = abs(rel)
        if (f.exists() && !f.isFile) throw IOException("refusing to replace non-file $f")
        val dir = f.parentFile ?: root
        dir.mkdirs()
        val tmp = File(dir, "${VaultRules.TMP_PREFIX}${System.nanoTime()}-${SEQ.getAndIncrement()}")
        try {
            java.io.FileInputStream(src).use { ins ->
                java.io.FileOutputStream(tmp).use { out ->
                    ins.copyTo(out)
                    out.fd.sync()
                }
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    override fun localFile(rel: String): File = abs(rel)

    override fun scan(): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles() ?: emptyArray()) {
                val rel = child.relativeTo(root).invariantSeparatorsPath
                if (!VaultKey.isValid(rel) || VaultRules.isIgnored(rel)) continue
                val p = child.toPath()
                if (java.nio.file.Files.isSymbolicLink(p)) continue
                if (child.isDirectory) stack.add(child) else if (child.isFile) out += rel
            }
        }
        out.sortWith { a, b -> Utf8.compare(a, b) }
        return out
    }

    private companion object {
        val SEQ = AtomicLong()
    }
}
