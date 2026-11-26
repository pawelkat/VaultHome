package com.vaulthome.app.ask

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ask settings. No API key lives here (or anywhere in the APK): Nitro is reached on the home LAN or over
 * Tailscale HTTPS.
 *
 * @property askEnabled feature flag for Ask: shows the compact Search ↔ Ask switch in the top bar (left of Settings). Off = Search only.
 * @property askMode last chosen mode of that switch (true = Ask), persisted across restarts.
 * @property nitroEnabled try the Nitro before the on-device path.
 * @property nitroBaseUrl root of VaultSearch's **chat proxy** on Nitro (`contrib/chat_proxy.py`), which injects
 *   `System/Nitro5 Qwen.md` + the date block and runs the `vault_search` tool loop server-side, then returns a
 *   plain chat completion. Home Wi‑Fi: [DEFAULT_NITRO_URL] (`:8082`); Tailscale: [TAILSCALE_NITRO_URL]. Raw
 *   llama-server on `:8080` answers without vault tools, so Ask must not point there.
 */
data class AskSettings(
    val askEnabled: Boolean = true,
    val nitroEnabled: Boolean = true,
    val nitroBaseUrl: String = DEFAULT_NITRO_URL,
    val askMode: Boolean = false,
    /** Try TowerX first when its Zenoh agent is alive and its chat proxy /health is OK (llama-server + vault-searchd). */
    val towerxEnabled: Boolean = true,
    val towerxBaseUrl: String = DEFAULT_TOWERX_URL,
    /** Provider the user pinned in the Providers panel; `null` = auto-pick (see [ProviderRouting]). */
    val pinnedProviderId: String? = null,
    /** Skills asked of the provider: `vault` (default), `code`, or both. */
    val skills: Set<String> = setOf(Skills.VAULT),
) {
    /** The search bar is in Ask mode only when Ask is enabled and the switch is on Ask. */
    val askActive: Boolean get() = askEnabled && askMode

    /** Where an Ask is sent first, for the remote/local badge next to the switch. */
    val target: AskTarget get() = if (nitroEnabled || towerxEnabled) AskTarget.Remote else AskTarget.Local

    companion object {
        /** Chat proxy on the home LAN (llama-server itself stays on 8080 behind it). */
        const val DEFAULT_NITRO_URL = "http://192.168.1.18:8082"

        /** TowerX chat proxy (`qwen-chat-proxy` :8082, `System/TowerX Qwen.md`) via Tailscale Serve. */
        const val DEFAULT_TOWERX_URL = "https://towerx.tailf52603.ts.net"

        /** Same chat proxy via Tailscale Serve (HTTPS, so no cleartext exception needed). */
        const val TAILSCALE_NITRO_URL = "https://nitro5.tailf52603.ts.net"

        /** PR-A's first default pointed at raw llama-server, which has no vault tools. */
        const val LEGACY_RAW_LLAMA_URL = "http://192.168.1.18:8080"

        /** A stored URL equal to the old raw-llama default moves to the chat proxy; custom URLs are kept. */
        fun migrateStoredUrl(stored: String?): String =
            if (stored == null || stored == LEGACY_RAW_LLAMA_URL) DEFAULT_NITRO_URL else stored

        /** Stored `code,vault` → set; blank or unknown values fall back to vault only. */
        fun parseSkills(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it == Skills.VAULT || it == Skills.CODE }.toSet().ifEmpty { setOf(Skills.VAULT) }

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

/** Where Ask goes first: Nitro over the network, or the phone itself. */
enum class AskTarget { Remote, Local }

/** Text of the small remote/local badge shown next to the top-bar switch in Ask mode. */
object AskBadge {
    fun label(target: AskTarget, host: String = "Nitro"): String = when (target) {
        AskTarget.Remote -> "Remote · $host"
        AskTarget.Local -> "Local · on-device"
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
        askMode = prefs.getBoolean(KEY_MODE, false),
        towerxEnabled = prefs.getBoolean(KEY_TOWERX, true),
        towerxBaseUrl = prefs.getString(KEY_TOWERX_URL, null) ?: AskSettings.DEFAULT_TOWERX_URL,
        pinnedProviderId = prefs.getString(KEY_PIN, null)?.takeIf { it.isNotBlank() },
        skills = AskSettings.parseSkills(prefs.getString(KEY_SKILLS, null)),
    )

    fun update(transform: (AskSettings) -> AskSettings) {
        val next = transform(flow.value).let { it.copy(nitroBaseUrl = AskSettings.normalizeBaseUrl(it.nitroBaseUrl)) }
        prefs.edit()
            .putBoolean(KEY_ASK, next.askEnabled)
            .putBoolean(KEY_NITRO, next.nitroEnabled)
            .putString(KEY_URL, next.nitroBaseUrl)
            .remove(KEY_GROUND) // retired: Ask no longer sends local hits to Nitro
            .putBoolean(KEY_MODE, next.askMode)
            .putBoolean(KEY_TOWERX, next.towerxEnabled)
            .putString(KEY_TOWERX_URL, next.towerxBaseUrl)
            .putString(KEY_PIN, next.pinnedProviderId)
            .putString(KEY_SKILLS, next.skills.sorted().joinToString(","))
            .apply()
        flow.value = next
    }

    private companion object {
        const val KEY_ASK = "ask_enabled"
        const val KEY_NITRO = "nitro_enabled"
        const val KEY_URL = "nitro_base_url"
        const val KEY_GROUND = "nitro_ground_with_hits"
        const val KEY_MODE = "search_bar_ask_mode"
        const val KEY_TOWERX = "towerx_enabled"
        const val KEY_TOWERX_URL = "towerx_base_url"
        const val KEY_PIN = "pinned_provider"
        const val KEY_SKILLS = "ask_skills"
    }
}
