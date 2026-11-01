package com.vaulthome.app.markdown

import java.net.URI
import java.net.URLDecoder

/**
 * Recognises links that point at a vault note rather than the web, so the app can open them in-app
 * (rendered from the local vault store) instead of the browser. Nitro's chat proxy cites notes as
 * `https://<nitro-host>/vault/<path>`; models also emit `/vault/<path>`, bare `Folder/Note.md` paths
 * and `obsidian://open?file=…`. Returns the vault-relative target (no leading slash, fragment/query
 * dropped, percent-decoded), to be resolved with [WikiLinks.resolve]; null for ordinary web links.
 */
object VaultLinks {
    private const val PREFIX = "/vault/"

    /** @param nitroHosts hosts whose `/vault/…` URLs are vault notes (configured Nitro URL + presets). */
    fun target(raw: String, nitroHosts: Collection<String>): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val uri = runCatching { URI(s.replace(" ", "%20")) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (nitroHosts.none { it.equals(host, ignoreCase = true) }) return null
            val path = uri.rawPath ?: return null
            if (!path.startsWith(PREFIX)) return null
            return clean(path.removePrefix(PREFIX))
        }
        if (lower.startsWith("obsidian://")) {
            val q = s.substringAfter('?', "")
            val file = q.split('&').firstOrNull { it.startsWith("file=") }?.removePrefix("file=") ?: return null
            return clean(file)
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) return null // mailto:, tel:, geo: …
        if (s.startsWith(PREFIX)) return clean(s.removePrefix(PREFIX))
        if (s.startsWith("vault/")) return clean(s.removePrefix("vault/"))
        // Bare vault-relative path: only when it looks like a note file, to avoid swallowing odd text.
        val bare = s.substringBefore('#').substringBefore('?')
        return if (bare.endsWith(".md", ignoreCase = true) || bare.endsWith(".markdown", ignoreCase = true)) clean(s) else null
    }

    /** Hosts of the configured Nitro base URL plus the known presets. */
    fun hostsOf(vararg baseUrls: String?): Set<String> = baseUrls.mapNotNull { u ->
        u?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
    }.toSet()

    private fun clean(p: String): String? {
        val noFrag = p.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(noFrag.replace("+", "%2B"), "UTF-8") }.getOrDefault(noFrag)
        return decoded.trim().trimStart('/').takeIf { it.isNotEmpty() }
    }
}
