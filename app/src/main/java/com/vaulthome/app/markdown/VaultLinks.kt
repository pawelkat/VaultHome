package com.vaulthome.app.markdown

import java.net.URI
import java.net.URLDecoder

/**
 * Maps a link in an Ask answer or rendered note to a vault-relative path, without assuming a host or provider.
 *
 * Recognised forms: `obsidian://open?file=…`, `vault://…`, `/vault/…` and `vault/…`, bare `*.md` paths, and http(s)
 * URLs whose path (after decoding and dropping query/fragment) ends in a path that exists in the vault. Known prefixes
 * (`/vault/`, `/files/`) and optional provider [linkBases] (e.g. `https://towerx…/vault/`) are stripped first; any
 * remaining prefix segments are tried as a longest-suffix match against the local index. Returns null when nothing
 * matches, so the caller can fall back to the browser.
 */
object VaultLinks {
    /** Path prefixes used by the chat proxies (Nitro, TowerX) and similar file servers. */
    val KNOWN_PATH_PREFIXES = listOf("/vault/", "/files/")

    /**
     * Resolve [raw] to an indexed vault path. [paths] are vault-relative (e.g. `Daily/2026-11-26.md`).
     * [linkBases] are optional provider URL prefixes (caps `linkBase`, or `<proxy>/vault/`).
     */
    fun resolve(raw: String, paths: Collection<String>, linkBases: Collection<String> = emptyList()): String? {
        val candidate = candidate(raw, linkBases) ?: return null
        if (isHttp(raw) || looksLikeUrlPath(candidate)) return matchSuffix(candidate, paths)
        return WikiLinks.resolve(candidate, paths)
    }

    /**
     * The vault-relative string a link is trying to name, or null when it is clearly not a note
     * (`mailto:`, `#heading`, a URL with no path, …). Does not check that the note exists.
     */
    fun candidate(raw: String, linkBases: Collection<String> = emptyList()): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        val lower = s.lowercase()
        if (isHttp(s)) {
            val uri = runCatching { URI(s.replace(" ", "%20")) }.getOrNull() ?: return null
            afterLinkBase(s, linkBases)?.let { return it }
            val path = uri.rawPath ?: return null
            for (prefix in KNOWN_PATH_PREFIXES) {
                if (path.startsWith(prefix) || path.startsWith(prefix.drop(1))) {
                    return clean(path.removePrefix(prefix).removePrefix(prefix.drop(1)))
                }
            }
            return clean(path)
        }
        if (lower.startsWith("obsidian://")) {
            val q = s.substringAfter('?', "")
            val file = q.split('&').firstOrNull { it.startsWith("file=", ignoreCase = true) }
                ?.substringAfter('=') ?: return null
            return clean(file)
        }
        if (lower.startsWith("vault:")) {
            // vault://Notes/A.md, vault:///Notes/A.md, vault:Notes/A.md
            return clean(s.substringAfter(':').trimStart('/').removePrefix("vault/"))
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) return null
        for (prefix in KNOWN_PATH_PREFIXES) {
            if (s.startsWith(prefix) || s.startsWith(prefix.drop(1))) {
                return clean(s.removePrefix(prefix).removePrefix(prefix.drop(1)))
            }
        }
        val bare = s.substringBefore('#').substringBefore('?')
        return if (bare.endsWith(".md", ignoreCase = true) || bare.endsWith(".markdown", ignoreCase = true)) clean(s) else null
    }

    /**
     * Longest suffix of [candidate] that equals a vault path (with or without `.md`). Exact equality wins over a
     * shorter suffix. Single-segment names (`health`) are not matched, so `/health` does not steal `health.md`.
     */
    fun matchSuffix(candidate: String, paths: Collection<String>): String? {
        val want = key(candidate) ?: return null
        val indexed = paths.mapNotNull { p -> key(p)?.let { it to p } }
        indexed.firstOrNull { it.first == want }?.let { return it.second }
        if ('/' !in want && !hasNoteExt(candidate)) return null
        return indexed
            .filter { want.endsWith("/${it.first}") }
            .maxWithOrNull(compareBy({ it.first.length }, { -it.second.count { c -> c == '/' } }, { -it.second.length }))
            ?.second
    }

    /** Hosts of configured proxy URLs, for diagnostics; resolution itself is not host-limited. */
    fun hostsOf(vararg baseUrls: String?): Set<String> = baseUrls.mapNotNull { hostOf(it) }.toSet()

    /**
     * Link bases derived from proxy roots (`<base>/vault/`) and any caps `linkBase` values.
     * Trailing slash is normalised so prefix-stripping is reliable.
     */
    fun linkBasesOf(baseUrls: Collection<String?>, extra: Collection<String> = emptyList()): List<String> =
        (baseUrls.mapNotNull { u ->
            u?.trim()?.takeIf { it.isNotEmpty() }?.let { AskSettingsNorm.base(it).trimEnd('/') + "/vault/" }
        } + extra.map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.endsWith("/")) it else "$it/" })
            .distinctBy { it.lowercase() }

    private fun afterLinkBase(raw: String, bases: Collection<String>): String? {
        val url = raw.trim()
        for (base in bases) {
            val b = base.trim().let { if (it.endsWith("/")) it else "$it/" }
            if (url.startsWith(b, ignoreCase = true)) return clean(url.removePrefix(url.take(b.length)))
            // Compare without scheme in case http vs https differs
        }
        // Also match by host+path prefix: "https://host/vault/" vs the URL
        val uri = runCatching { URI(url.replace(" ", "%20")) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val path = uri.rawPath ?: return null
        for (base in bases) {
            val b = runCatching { URI(base.trim().replace(" ", "%20")) }.getOrNull() ?: continue
            if (!host.equals(b.host, ignoreCase = true)) continue
            val prefix = (b.rawPath ?: "/").let { if (it.endsWith("/")) it else "$it/" }
            if (path.startsWith(prefix) || path.startsWith(prefix.removePrefix("/"))) {
                return clean(path.removePrefix(prefix).removePrefix(prefix.removePrefix("/")))
            }
        }
        return null
    }

    private fun isHttp(s: String): Boolean {
        val l = s.lowercase()
        return l.startsWith("http://") || l.startsWith("https://")
    }

    private fun looksLikeUrlPath(candidate: String) = candidate.contains('/') || hasNoteExt(candidate)

    private fun hasNoteExt(p: String) =
        p.endsWith(".md", ignoreCase = true) || p.endsWith(".markdown", ignoreCase = true)

    private fun key(p: String): String? {
        val t = p.trim().trimStart('/').removeSuffix(".md").removeSuffix(".markdown").lowercase()
        return t.takeIf { it.isNotEmpty() }
    }

    private fun hostOf(baseUrl: String?): String? =
        baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }

    private fun clean(p: String): String? {
        val noFrag = p.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(noFrag.replace("+", "%2B"), "UTF-8") }.getOrDefault(noFrag)
        return decoded.trim().trimStart('/').takeIf { it.isNotEmpty() }
    }
}

/** Local helper so this file does not depend on the Ask settings class. */
private object AskSettingsNorm {
    fun base(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        url = url.trimEnd('/')
        if (url.endsWith("/v1")) url = url.removeSuffix("/v1").trimEnd('/')
        if (url.endsWith("/chat/completions")) url = url.removeSuffix("/chat/completions").trimEnd('/')
        if (url.endsWith("/v1")) url = url.removeSuffix("/v1").trimEnd('/')
        return url
    }
}
