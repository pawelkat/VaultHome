package com.vaulthome.app.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class VaultFile(
    val relativePath: String,
    val mtime: Long,
    val uri: Uri,
)

/**
 * Read-only view of the granted vault tree.
 * This class never creates, renames, or writes a vault file.
 */
class VaultReader(
    private val context: Context,
    private val store: VaultStore,
) {
    private var cache: List<VaultFile>? = null

    private var imageCache: List<VaultFile>? = null

    fun invalidate() {
        cache = null
        imageCache = null
    }

    /** All image files in the vault tree (for Obsidian attachment embeds). Cached like [scan]. */
    fun scanImages(): List<VaultFile> {
        imageCache?.let { return it }
        val rootUri = store.treeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val found = mutableListOf<VaultFile>()
        walk(root, "", found) { com.vaulthome.app.markdown.Images.isImage(it) }
        return found.also { imageCache = it }
    }

    fun scan(): List<VaultFile> {
        cache?.let { return it }
        val rootUri = store.treeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val found = mutableListOf<VaultFile>()
        walk(root, "", found, ::isMarkdown)
        return found.also { cache = it }
    }

    fun readText(uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_BYTES) return null
        if (bytes.any { it == 0.toByte() }) return null
        return bytes.toString(Charsets.UTF_8)
    }

    fun readNote(relativePath: String): String? {
        val file = scan().firstOrNull { it.relativePath == relativePath } ?: return null
        return readText(file.uri)
    }

    /**
     * Reads one note by vault-relative path, walking the tree by name instead of the cached [scan],
     * so a file that appeared after the last scan (e.g. today's `Panel/YYYY-MM-DD.md`) is found.
     * Read-only, like everything here.
     */
    fun readFresh(relativePath: String): String? {
        val rootUri = store.treeUri() ?: return null
        var node: DocumentFile = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            node = node.findFile(segment) ?: return null
        }
        if (!node.isFile) return null
        return readText(node.uri)
    }

    private fun walk(directory: DocumentFile, prefix: String, out: MutableList<VaultFile>, accept: (String) -> Boolean) {
        for (child in directory.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> walk(child, path, out, accept)
                child.isFile && accept(name) -> out += VaultFile(path, child.lastModified(), child.uri)
            }
        }
    }

    private fun isMarkdown(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".markdown")
    }

    private companion object {
        const val MAX_BYTES = 1_000_000
    }
}
