package com.vaulthome.app.ask

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ask settings. No API key lives here (or anywhere in the APK): Nitro is reached on the home LAN or over
 * Tailscale HTTPS.
 *
 * @property askEnabled feature flag for the Ask surface (top-bar icon). Off = app behaves like before.
 * @property nitroEnabled try the Nitro before the on-device path.
 * @property nitroBaseUrl root of VaultSearch's **chat proxy** on Nitro (`contrib/chat_proxy.py`), which injects
 *   `System/Nitro5 Qwen.md` + the date block and runs the `vault_search` tool loop server-side, then returns a
 *   plain chat completion. Home Wi‑Fi: [DEFAULT_NITRO_URL] (`:8082`); Tailscale: [TAILSCALE_NITRO_URL]. Raw
 *   llama-server on `:8080` answers without vault tools, so Ask must not point there.
 * @property groundNitro also send the local EmbeddingGemma hits to Nitro as context. Off by default:
 *   in PR-A local hits are citations only (see the PR description).
 */
data class AskSettings(
    val askEnabled: Boolean = true,
    val nitroEnabled: Boolean = true,
    val nitroBaseUrl: String = DEFAULT_NITRO_URL,
    val groundNitro: Boolean = false,
) {
    companion object {
        /** Chat proxy on the home LAN (llama-server itself stays on 8080 behind it). */
        const val DEFAULT_NITRO_URL = "http://192.168.1.18:8082"

        /** Same chat proxy via Tailscale Serve (HTTPS, so no cleartext exception needed). */
        const val TAILSCALE_NITRO_URL = "https://nitro5.tailf52603.ts.net"

        /** PR-A's first default pointed at raw llama-server, which has no vault tools. */
        const val LEGACY_RAW_LLAMA_URL = "http://192.168.1.18:8080"

        /** A stored URL equal to the old raw-llama default moves to the chat proxy; custom URLs are kept. */
        fun migrateStoredUrl(stored: String?): String =
            if (stored == null || stored == LEGACY_RAW_LLAMA_URL) DEFAULT_NITRO_URL else stored

        /** Trims, adds `http://` when no scheme is typed, drops trailing slashes and a trailing `/v1`. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_NITRO_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            url = url.trimEnd('/')
            if (url.endsWith("/v1")) url = url.removeSuffix("/v1").trimEnd('/')
            return url
        }
    }
}

class AskSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vaulthome_ask", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(load())
    val state: StateFlow<AskSettings> = flow

    private fun load() = AskSettings(
        askEnabled = prefs.getBoolean(KEY_ASK, true),
        nitroEnabled = prefs.getBoolean(KEY_NITRO, true),
        nitroBaseUrl = AskSettings.migrateStoredUrl(prefs.getString(KEY_URL, null)),
        groundNitro = prefs.getBoolean(KEY_GROUND, false),
    )

    fun update(transform: (AskSettings) -> AskSettings) {
        val next = transform(flow.value).let { it.copy(nitroBaseUrl = AskSettings.normalizeBaseUrl(it.nitroBaseUrl)) }
        prefs.edit()
            .putBoolean(KEY_ASK, next.askEnabled)
            .putBoolean(KEY_NITRO, next.nitroEnabled)
            .putString(KEY_URL, next.nitroBaseUrl)
            .putBoolean(KEY_GROUND, next.groundNitro)
            .apply()
        flow.value = next
    }

    private companion object {
        const val KEY_ASK = "ask_enabled"
        const val KEY_NITRO = "nitro_enabled"
        const val KEY_URL = "nitro_base_url"
        const val KEY_GROUND = "nitro_ground_with_hits"
    }
}
