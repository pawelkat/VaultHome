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

    fun invalidate() {
        cache = null
    }

    fun scan(): List<VaultFile> {
        cache?.let { return it }
        val rootUri = store.treeUri() ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val found = mutableListOf<VaultFile>()
        walk(root, "", found)
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

    private fun walk(directory: DocumentFile, prefix: String, out: MutableList<VaultFile>) {
        for (child in directory.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> walk(child, path, out)
                child.isFile && isMarkdown(name) -> out += VaultFile(path, child.lastModified(), child.uri)
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
