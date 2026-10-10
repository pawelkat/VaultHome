package com.vaulthome.app.index

import java.net.URLEncoder
import java.nio.charset.Charset

object ObsidianLinks {
    const val VAULT_NAME = "Vault"

    fun vaultFileUri(relativePath: String): String {
        val file = relativePath.removeSuffix(".md").removeSuffix(".markdown")
        return "obsidian://open?vault=$VAULT_NAME&file=${encodePath(file)}"
    }

    /** Used when the Obsidian vault name is not [VAULT_NAME]. */
    fun pathFallbackUri(absolutePath: String): String {
        return "obsidian://open?path=${encodePath(absolutePath)}"
    }

    fun absoluteVaultPath(relativePath: String): String {
        val root = SearchConfig.VAULT_ROOT.trimEnd('/')
        val relative = relativePath.trimStart('/')
        return "$root/$relative"
    }

    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charset.forName("UTF-8")).replace("+", "%20")
        }
    }
}
