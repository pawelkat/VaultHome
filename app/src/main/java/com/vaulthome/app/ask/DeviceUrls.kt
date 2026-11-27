package com.vaulthome.app.ask

import com.vaulthome.app.devices.RegDevice

/**
 * One tailnet suffix for the whole app, used to build `https://<device>.<suffix>` for any host.
 *
 * Taken from the configured Zenoh router endpoint when that host is a `*.ts.net` name
 * (e.g. `tcp/n910c.tailf52603.ts.net:7447` → `tailf52603.ts.net`). Otherwise the fallback constant.
 */
object TailnetUrls {
    const val FALLBACK_SUFFIX = "tailf52603.ts.net"

    /** Host of a Zenoh endpoint such as `tcp/n910c.tailf52603.ts.net:7447` or `tcp/[::1]:7447`. */
    fun hostOf(endpoint: String): String? {
        val rest = endpoint.substringAfter('/', missingDelimiterValue = "").trim()
        if (rest.isEmpty()) return null
        if (rest.startsWith('[')) return rest.substringAfter('[').substringBefore(']').takeIf { it.isNotBlank() }
        return rest.substringBefore(':').substringBefore('/').takeIf { it.isNotBlank() }
    }

    /**
     * `machine.<tailnet>.ts.net` → `<tailnet>.ts.net`. A host that is not under `.ts.net`, or is only `ts.net`,
     * yields null so the caller can use [FALLBACK_SUFFIX].
     */
    fun suffixOfHost(host: String): String? {
        if (!host.endsWith(".ts.net", ignoreCase = true)) return null
        val rest = host.substringAfter('.', missingDelimiterValue = "")
        return rest.takeIf { it.endsWith(".ts.net", ignoreCase = true) && !it.equals("ts.net", ignoreCase = true) }?.lowercase()
    }

    fun suffix(routerEndpoints: List<String>, fallback: String = FALLBACK_SUFFIX): String =
        routerEndpoints.firstNotNullOfOrNull { hostOf(it)?.let(::suffixOfHost) } ?: fallback

    fun derived(device: String, suffix: String): String = "https://$device.$suffix"
}

enum class UrlSource { Manifest, Registry, Derived }

/** Chat-proxy base URLs for one device. [primary] is what Ask uses first; [lan] is tried only when devices.json listed it. */
data class ResolvedUrls(val primary: String, val lan: String?, val source: UrlSource) {
    /** Tailnet (or manifest / registry override) first; a lan endpoint is appended only when the registry listed one. */
    fun urls(): List<String> = listOf(primary) + listOfNotNull(lan?.takeIf { it != primary })
}

/**
 * Resolves a device's chat-proxy base URL.
 *
 * Override order: caps manifest endpoint, then a devices.json `chat_proxy` or `endpoints.tailnet`, then the
 * derived `https://<device>.<suffix>`. A `lan` endpoint is never invented and is never preferred over the tailnet URL;
 * it is only tried when the registry lists one.
 */
object DeviceUrls {
    /** Chat-proxy root: drop a trailing `/v1/chat/completions` or `/v1`, then [AskSettings.normalizeBaseUrl]. */
    fun asBase(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/chat/completions")) url = url.removeSuffix("/chat/completions").trimEnd('/')
        return AskSettings.normalizeBaseUrl(url)
    }

    fun resolve(
        device: String,
        suffix: String,
        registry: RegDevice? = null,
        manifestEndpoint: String? = null,
    ): ResolvedUrls {
        val lan = registry?.endpoints?.get("lan")?.let { asBase(it) }?.takeIf { it.isNotBlank() }
        val fromManifest = manifestEndpoint?.let { asBase(it) }?.takeIf { it.isNotBlank() }
        if (fromManifest != null) return ResolvedUrls(fromManifest, lan = null, UrlSource.Manifest)
        val fromRegistry = (registry?.chatProxy ?: registry?.endpoints?.get("tailnet"))
            ?.let { asBase(it) }?.takeIf { it.isNotBlank() }
        if (fromRegistry != null) return ResolvedUrls(fromRegistry, lan, UrlSource.Registry)
        return ResolvedUrls(TailnetUrls.derived(device, suffix), lan, UrlSource.Derived)
    }

    /** A registry device that can be a chat backend when no caps manifest has arrived yet. */
    fun isChatHost(d: RegDevice): Boolean =
        !d.isPowerBoard && (d.chatProxy != null || d.kind == RegDevice.KIND_PC || "tailnet" in d.endpoints || "lan" in d.endpoints)
}
