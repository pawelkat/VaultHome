package com.vaulthome.app.ui

import android.content.Context
import android.widget.Toast
import com.vaulthome.app.VaultHomeApp
import com.vaulthome.app.ask.DeviceUrls
import com.vaulthome.app.ask.TailnetUrls
import com.vaulthome.app.markdown.VaultLinks
import com.vaulthome.app.markdown.WikiLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tap path for note links in rendered markdown (Ask answers and notes): vault links — Nitro's
 * `https://<nitro>/vault/…` citations, `/vault/…`, bare `*.md` paths, `obsidian://open?file=` — and
 * wikilinks are resolved against the local index and opened in-app; everything else goes to [LinkOpener].
 */
object VaultLinkOpener {
    fun nitroHosts(context: Context): Set<String> {
        val graph = (context.applicationContext as VaultHomeApp).graph
        val suffix = TailnetUrls.suffix(graph.eventSync.state.value.connect)
        val urls = graph.deviceRegistry.state.value.registry.devices
            .filter(DeviceUrls::isChatHost)
            .flatMap { DeviceUrls.resolve(it.id, suffix, it).urls() }
        return VaultLinks.hostsOf(*urls.toTypedArray())
    }

    /** Markdown link tap. */
    fun onLink(context: Context, scope: CoroutineScope, raw: String, current: String? = null, onOpenNote: (String) -> Unit) {
        val target = VaultLinks.target(raw, nitroHosts(context))
        if (target == null) { LinkOpener.open(context, raw); return }
        openTarget(context, scope, target, current, onOpenNote, webFallback = raw)
    }

    /** Wikilink tap. */
    fun onWikiLink(context: Context, scope: CoroutineScope, target: String, current: String? = null, onOpenNote: (String) -> Unit) =
        openTarget(context, scope, target, current, onOpenNote, webFallback = null)

    private fun openTarget(context: Context, scope: CoroutineScope, target: String, current: String?, onOpenNote: (String) -> Unit, webFallback: String?) {
        scope.launch {
            val graph = (context.applicationContext as VaultHomeApp).graph
            val paths = withContext(Dispatchers.IO) { graph.database.chunks().allNotes().map { it.path } }
            val resolved = WikiLinks.resolve(target, paths)
            when {
                resolved == null -> {
                    Toast.makeText(context, "Note not in the local vault: $target", Toast.LENGTH_SHORT).show()
                    // A web URL (e.g. Nitro's /vault/ page) still works when the local copy is missing.
                    if (webFallback != null && webFallback.startsWith("http", ignoreCase = true)) LinkOpener.open(context, webFallback)
                }
                resolved != current -> onOpenNote(resolved)
            }
        }
    }
}
