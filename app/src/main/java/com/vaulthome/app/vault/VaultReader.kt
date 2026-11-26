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
     * One file by relative vault path, looked up on the tree (not the [scan] cache). Used so today's
     * `Daily/yyyy-MM-dd.md` is found even when the last index run is a few days old.
     */
    fun findByPath(relativePath: String): VaultFile? {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val rootUri = store.treeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        var dir = root
        for ((i, part) in parts.withIndex()) {
            val child = dir.findFile(part) ?: return null
            if (i == parts.lastIndex) return if (child.isFile) VaultFile(relativePath, child.lastModified(), child.uri) else null
            if (!child.isDirectory) return null
            dir = child
        }
        return null
    }

    /** Markdown files directly in [folder] (e.g. `Daily`), without walking the rest of the vault. */
    fun listFolder(folder: String): List<VaultFile> {
        val rootUri = store.treeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        var dir = root
        if (folder.isNotEmpty()) {
            for (part in folder.split('/').filter { it.isNotEmpty() }) {
                dir = dir.findFile(part) ?: return emptyList()
                if (!dir.isDirectory) return emptyList()
            }
        }
        return dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            if (child.isFile && isMarkdown(name)) {
                val path = if (folder.isEmpty()) name else "$folder/$name"
                VaultFile(path, child.lastModified(), child.uri)
            } else null
        }
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
