package com.vaulthome.app.x4

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the XTEINK X4 e-ink panel's MCP server lives (`POST {baseUrl}/mcp`, port 80, plain http).
 * The address is DHCP: [DEFAULT_X4_URL] is only the last known lease (boot of 2026-11-01). The panel prints its
 * current address on the glass; type that here when it changes. VaultHome never guesses other hosts.
 */
data class X4Settings(val baseUrl: String = DEFAULT_X4_URL) {
    companion object {
        /** Last DHCP lease seen on the X4 (vault note "Grok Bot - X4 markdown"). */
        const val DEFAULT_X4_URL = "http://192.168.1.27"

        /** CrowPanel 5.79 (`vault-home-panel` default). Never a target for notes or answers. */
        const val CROWPANEL_HOST = "192.168.1.26"

        /** Trims, adds `http://`, drops trailing slashes and a trailing `/mcp`. Blank → [DEFAULT_X4_URL]. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_X4_URL
            if (!url.contains("://")) url = "http://$url"
            url = url.trimEnd('/')
            if (url.endsWith("/mcp")) url = url.removeSuffix("/mcp").trimEnd('/')
            return url
        }

        /** Host part of a normalized base URL, e.g. `192.168.1.27` from `http://192.168.1.27:80`. */
        fun host(baseUrl: String): String =
            baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')

        fun port(baseUrl: String): Int =
            baseUrl.substringAfter("://").substringBefore('/').substringAfter(':', "80").toIntOrNull() ?: 80

        /**
         * Why [baseUrl] must not be used, or null when it is fine. Only plain http to a LAN/loopback host; never
         * the CrowPanel.
         */
        fun problem(baseUrl: String): String? {
            if (!baseUrl.startsWith("http://")) return "The X4 speaks plain http only (http://<ip>)"
            val host = host(baseUrl)
            if (host.isEmpty()) return "No X4 host set"
            if (host == CROWPANEL_HOST) return "$CROWPANEL_HOST is the CrowPanel, not the X4"
            if (!isLanHost(host)) return "X4 host must be on the home LAN (192.168.x.x, 10.x, 172.16–31.x or localhost)"
            return null
        }

        /** Private IPv4 literal, loopback, or a `.local` / `.lan` / `.home` name. */
        fun isLanHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true
            val parts = h.split('.')
            if (parts.size != 4) return false
            val n = parts.map { it.toIntOrNull() ?: return false }
            if (n.any { it !in 0..255 }) return false
            return n[0] == 10 || n[0] == 127 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
        }
    }
}

class X4SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome_x4", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(X4Settings(prefs.getString(KEY_URL, null) ?: X4Settings.DEFAULT_X4_URL))
    val state: StateFlow<X4Settings> = flow

    fun setBaseUrl(raw: String) {
        val next = X4Settings(X4Settings.normalizeBaseUrl(raw))
        prefs.edit().putString(KEY_URL, next.baseUrl).apply()
        flow.value = next
    }

    private companion object { const val KEY_URL = "x4_base_url" }
}
