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
}

/**
 * Where to store a conflict copy of [bytes]: [base] if free, else ` 2`, ` 3`, ...; `null` when a
 * candidate already holds exactly these bytes (copy already made, possibly by another device).
 */
fun VaultTree.conflictSlot(base: String, bytes: ByteArray): String? {
    val want = sha256Hex(bytes)
    for (n in 1 until 1000) {
        val cand = if (n == 1) base else VaultRules.numbered(base, n)
        when (val d = read(cand)) {
            is Disk.Absent -> return cand
            is Disk.Present -> if (d.sha256 == want) return null
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
