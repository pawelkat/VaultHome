package com.vaulthome.app.ask

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ask settings. No API key lives here (or anywhere in the APK): the Nitro llama-server is LAN-only.
 *
 * @property askEnabled feature flag for the Ask surface (top-bar icon). Off = app behaves like before.
 * @property nitroEnabled try the Nitro before the on-device path.
 * @property nitroBaseUrl llama-server root, e.g. `http://192.168.1.18:8080`.
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
        const val DEFAULT_NITRO_URL = "http://192.168.1.18:8080"

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
        nitroBaseUrl = prefs.getString(KEY_URL, null) ?: AskSettings.DEFAULT_NITRO_URL,
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
