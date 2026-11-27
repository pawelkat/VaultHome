package com.vaulthome.app.ui

import android.content.Context
import android.widget.Toast
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.AskSettings
import com.vaulthome.app.ask.ProviderMonitor
import com.vaulthome.app.markdown.VaultLinks
import com.vaulthome.app.markdown.WikiLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tap path for note links in rendered markdown (Ask answers and notes). A link is resolved against the
 * local vault (index, then a direct file lookup) without assuming which host issued it: Nitro, TowerX, or
 * any later provider. A match opens in-app; only when nothing resolves does the browser open.
 */
object VaultLinkOpener {
    fun linkBases(context: Context): List<String> {
        val graph = (context.applicationContext as VaultHomeApp).graph
        val s = graph.askSettings.state.value
        val fromCaps = ProviderMonitor.state.value.manifests.values.flatten().mapNotNull { it.linkBase }
        val fromEndpoints = ProviderMonitor.state.value.manifests.values.flatten()
            .filter { it.isChat }.map { it.baseUrl }
        return VaultLinks.linkBasesOf(
            listOf(
                s.nitroBaseUrl, s.towerxBaseUrl,
                AskSettings.TAILSCALE_NITRO_URL, AskSettings.DEFAULT_NITRO_URL, AskSettings.DEFAULT_TOWERX_URL,
            ) + fromEndpoints,
            extra = fromCaps,
        )
    }

    /** Markdown link tap. */
    fun onLink(context: Context, scope: CoroutineScope, raw: String, current: String? = null, onOpenNote: (String) -> Unit) {
        scope.launch {
            val graph = (context.applicationContext as VaultHomeApp).graph
            val bases = linkBases(context)
            val resolved = withContext(Dispatchers.IO) {
                val paths = graph.database.chunks().allNotes().map { it.path }
                VaultLinks.resolve(raw, paths, bases)
                    ?: filesystemMatch(graph, VaultLinks.candidate(raw, bases))
            }
            when {
                resolved == null -> {
                    if (raw.startsWith("http", ignoreCase = true)) LinkOpener.open(context, raw)
                    else Toast.makeText(context, "Note not in the local vault: $raw", Toast.LENGTH_SHORT).show()
                }
                resolved != current -> onOpenNote(resolved)
            }
        }
    }

    /** Wikilink tap. */
    fun onWikiLink(context: Context, scope: CoroutineScope, target: String, current: String? = null, onOpenNote: (String) -> Unit) {
        scope.launch {
            val graph = (context.applicationContext as VaultHomeApp).graph
            val resolved = withContext(Dispatchers.IO) {
                val paths = graph.database.chunks().allNotes().map { it.path }
                WikiLinks.resolve(target, paths) ?: filesystemMatch(graph, target)
            }
            when {
                resolved == null -> Toast.makeText(context, "Note not in the local vault: $target", Toast.LENGTH_SHORT).show()
                resolved != current -> onOpenNote(resolved)
            }
        }
    }

    /** When the index is behind the tree, a note still opens if the file is on disk. */
    private fun filesystemMatch(graph: com.vaulthome.app.AppGraph, candidate: String?): String? {
        val t = candidate?.substringBefore('#')?.substringBefore('|')?.trim()?.trimStart('/') ?: return null
        if (t.isEmpty()) return null
        val tried = listOf(t, "$t.md", "$t.markdown").distinct()
        return tried.firstOrNull { graph.reader.findByPath(it) != null }
    }
}
