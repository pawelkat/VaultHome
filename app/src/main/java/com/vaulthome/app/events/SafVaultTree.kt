package com.vaulthome.app.events

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.vaulthome.events.Disk
import com.vaulthome.events.VaultKey
import com.vaulthome.events.VaultRules
import com.vaulthome.events.VaultTree
import com.vaulthome.events.sha256Hex
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * [VaultTree] over VaultHome's SAF tree grant (the same tree the index reads). Writing needs the
 * grant to include write permission (Settings → Event sync → Grant write access).
 *
 * SAF has no atomic replace: a new note is written to a `.vault-events-tmp-*` file and renamed into
 * place; an existing note is truncated and rewritten in place ("wt"). Names the provider would
 * change (FAT-invalid characters such as `?`, `*`, `:`, `"`) are refused instead of synced under a
 * different name.
 */
class SafVaultTree(
    context: Context,
    private val tree: Uri,
    private val maxBytes: Long = VaultRules.DEFAULT_MAX_BYTES,
) : VaultTree {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    /** ExternalStorageProvider document ids are paths (`primary:DriveSyncFiles/Vault/Notes/a.md`). */
    private val pathIds = tree.authority == "com.android.externalstorage.documents"

    /** rel -> document id, learned while walking (non-path providers). */
    private val ids = HashMap<String, String>()

    /** `(mtime, size)` from the last [scan] listing, so a rescan costs one query per folder, not per file. */
    private val statCache = HashMap<String, Pair<Long, Long>>()
    private var statCacheAt = 0L

    private data class Meta(val id: String, val name: String, val mime: String, val mtime: Long, val size: Long) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun meta(id: String): Meta? = try {
        resolver.query(docUri(id), PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else Meta(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4))
        }
    } catch (_: Exception) {
        null // FileNotFoundException / IllegalArgumentException from the provider = absent
    }

    private fun children(id: String): List<Meta> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Meta(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4))) }
        } ?: throw IOException("cannot list $id")
    }

    /** Document id for [rel] if it exists (any type). */
    private fun find(rel: String): Meta? {
        VaultKey.validateRel(rel)
        if (pathIds) return meta("$rootId/$rel")?.takeIf { it.name == rel.substringAfterLast('/') }
        ids[rel]?.let { id -> meta(id)?.takeIf { it.name == rel.substringAfterLast('/') }?.let { return it } }
        var cur = rootId
        var prefix = ""
        var found: Meta? = null
        for (part in rel.split('/')) {
            val m = children(cur).firstOrNull { it.name == part } ?: return null
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            ids[prefix] = m.id
            cur = m.id
            found = m
        }
        return found
    }

    override fun rootExists(): Boolean = meta(rootId)?.isDir == true

    override fun stat(rel: String): Pair<Long, Long>? {
        if (System.currentTimeMillis() - statCacheAt < STAT_CACHE_MS) statCache[rel]?.let { return it }
        val m = runCatching { find(rel) }.getOrNull() ?: return null
        return if (m.isDir) null else m.mtime to m.size
    }

    override fun read(rel: String): Disk {
        if (VaultRules.isIgnored(rel)) return Disk.Skipped("ignored path")
        val m = find(rel) ?: return Disk.Absent
        if (m.isDir) return Disk.Skipped("not a regular file")
        if (m.size > maxBytes) return Disk.Skipped("${m.size} bytes > max $maxBytes")
        val bytes = try {
            resolver.openInputStream(docUri(m.id))?.use { it.readBytes() } ?: return Disk.Absent
        } catch (_: java.io.FileNotFoundException) {
            return Disk.Absent
        }
        if (bytes.size > maxBytes) return Disk.Skipped("${bytes.size} bytes > max $maxBytes")
        return Disk.Present(sha256Hex(bytes), bytes, m.mtime, bytes.size.toLong())
    }

    private fun ensureDir(relDir: String): String {
        if (relDir.isEmpty()) return rootId
        find(relDir)?.let { if (it.isDir) return it.id else throw IOException("$relDir is a file") }
        val parent = ensureDir(relDir.substringBeforeLast('/', ""))
        val name = relDir.substringAfterLast('/')
        val uri = DocumentsContract.createDocument(resolver, docUri(parent), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("cannot create folder $relDir")
        val id = DocumentsContract.getDocumentId(uri)
        val m = meta(id)
        if (m?.name != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException("folder name \"$name\" is not representable on this storage")
        }
        ids[relDir] = id
        return id
    }

    override fun writeAtomic(rel: String, bytes: ByteArray) {
        statCache.remove(rel)
        val existing = find(rel)
        if (existing != null) {
            if (existing.isDir) throw IOException("refusing to replace non-file $rel")
            resolver.openOutputStream(docUri(existing.id), "wt")?.use { it.write(bytes) } ?: throw IOException("cannot write $rel")
            return
        }
        val name = rel.substringAfterLast('/')
        val parent = ensureDir(rel.substringBeforeLast('/', ""))
        val tmpName = "${VaultRules.TMP_PREFIX}${System.currentTimeMillis()}-${SEQ.getAndIncrement()}"
        val tmp = DocumentsContract.createDocument(resolver, docUri(parent), OCTET, tmpName)
            ?: throw IOException("cannot create temp file for $rel")
        try {
            resolver.openOutputStream(tmp, "w")?.use { it.write(bytes) } ?: throw IOException("cannot write $rel")
            val renamed = DocumentsContract.renameDocument(resolver, tmp, name) ?: tmp
            val m = meta(DocumentsContract.getDocumentId(renamed))
            if (m?.name != name) {
                runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
                throw IOException("file name \"$name\" is not representable on this storage")
            }
            ids[rel] = m.id
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, tmp) }
            throw if (e is IOException) e else IOException("write $rel: ${e.message}", e)
        }
    }

    override fun remove(rel: String) {
        statCache.remove(rel)
        val m = find(rel) ?: return
        if (m.isDir) throw IOException("refusing to delete non-file $rel")
        if (!DocumentsContract.deleteDocument(resolver, docUri(m.id))) throw IOException("cannot delete $rel")
        ids.remove(rel)
    }

    override fun scan(): List<String> {
        val out = ArrayList<String>()
        statCache.clear()
        val stack = ArrayDeque<Pair<String, String>>().apply { add(rootId to "") }
        while (stack.isNotEmpty()) {
            val (id, prefix) = stack.removeLast()
            for (c in children(id)) {
                val rel = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                if (!VaultKey.isValid(rel) || VaultRules.isIgnored(rel)) continue
                ids[rel] = c.id
                if (c.isDir) stack.add(c.id to rel) else { out += rel; statCache[rel] = c.mtime to c.size }
            }
        }
        statCacheAt = System.currentTimeMillis()
        out.sortWith { a, b -> compareUtf8(a, b) }
        return out
    }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    private companion object {
        const val OCTET = "application/octet-stream"
        const val STAT_CACHE_MS = 10_000L
        val SEQ = AtomicLong()
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}
