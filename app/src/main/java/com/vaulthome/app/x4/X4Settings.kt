package com.vaulthome.app.x4

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How to find the XTEINK X4 over BLE (vault note "Grok Bot - X4 BLE").
 * The flashed image (`baac775`) has no Wi-Fi MCP: match the advertised name and optional address hint.
 * There is no pairing step; the OS "available devices" list will not show it.
 */
data class X4Settings(
    val deviceName: String = X4Ble.DEFAULT_NAME,
    val addressHint: String = X4Ble.DEFAULT_ADDRESS,
) {
    val label: String get() = if (addressHint.isNotBlank()) "$deviceName · $addressHint" else deviceName

    companion object {
        fun normalizeName(raw: String): String = X4Ble.normalizeName(raw)
        fun normalizeAddress(raw: String): String = X4Ble.normalizeAddress(raw)

        /** Why [addressHint] must not be used as a scan filter, or null when it is a MAC. */
        fun addressProblem(addressHint: String): String? =
            if (X4Ble.isValidAddress(addressHint)) null
            else "Use a Bluetooth address like ${X4Ble.DEFAULT_ADDRESS}"

        // Kept for the unused Wi-Fi MCP client (X4McpClient) and its tests.
        const val DEFAULT_X4_URL = "http://192.168.1.27"
        const val CROWPANEL_HOST = "192.168.1.26"

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_X4_URL
            if (!url.contains("://")) url = "http://$url"
            url = url.trimEnd('/')
            if (url.endsWith("/mcp")) url = url.removeSuffix("/mcp").trimEnd('/')
            return url
        }

        fun host(baseUrl: String): String =
            baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')

        fun port(baseUrl: String): Int =
            baseUrl.substringAfter("://").substringBefore('/').substringAfter(':', "80").toIntOrNull() ?: 80

        fun problem(baseUrl: String): String? {
            if (!baseUrl.startsWith("http://")) return "The X4 speaks plain http only (http://<ip>)"
            val host = host(baseUrl)
            if (host.isEmpty()) return "No X4 host set"
            if (host == CROWPANEL_HOST) return "$CROWPANEL_HOST is the CrowPanel, not the X4"
            if (!isLanHost(host)) return "X4 host must be on the home LAN (192.168.x.x, 10.x, 172.16–31.x or localhost)"
            return null
        }

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
    private val flow = MutableStateFlow(
        X4Settings(
            deviceName = X4Settings.normalizeName(prefs.getString(KEY_NAME, null) ?: X4Ble.DEFAULT_NAME),
            addressHint = X4Settings.normalizeAddress(prefs.getString(KEY_ADDR, null) ?: X4Ble.DEFAULT_ADDRESS),
        ),
    )
    val state: StateFlow<X4Settings> = flow

    fun setDevice(name: String, address: String) {
        val next = X4Settings(
            deviceName = X4Settings.normalizeName(name),
            addressHint = X4Settings.normalizeAddress(address),
        )
        prefs.edit().putString(KEY_NAME, next.deviceName).putString(KEY_ADDR, next.addressHint).apply()
        flow.value = next
    }

    private companion object {
        const val KEY_NAME = "x4_ble_name"
        const val KEY_ADDR = "x4_ble_address"
    }
}
