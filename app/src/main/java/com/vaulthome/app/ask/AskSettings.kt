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
 * Chat-proxy base URLs are not stored here: they come from [DeviceUrls] (derived tailnet URL, devices.json
 * endpoints if present, caps manifest when one exists). Old stored URL prefs are ignored.
 *
 * @property excludedProviderIds providers the user turned off with "Don't use for Ask" on the Providers page,
 *   keyed by provider id (e.g. `nitro5-qwen`). They show as disabled, are not routed to, and cannot be pinned.
 */
data class AskSettings(
    val askEnabled: Boolean = true,
    val askMode: Boolean = false,
    /** Provider the user pinned in the Providers panel; `null` = auto-pick (see [ProviderRouting]). */
    val pinnedProviderId: String? = null,
    /** Skills asked of the provider: `vault` (default), `code`, or both. */
    val skills: Set<String> = setOf(Skills.VAULT),
    val excludedProviderIds: Set<String> = emptySet(),
) {
    /** The search bar is in Ask mode only when Ask is enabled and the switch is on Ask. */
    val askActive: Boolean get() = askEnabled && askMode

    /**
     * Where an Ask is sent first, for the remote/local badge next to the switch. Local only when both of today's
     * Settings chat providers are in [excludedProviderIds]; routing itself is unchanged.
     */
    val target: AskTarget get() {
        val known = setOf(ProviderRouting.NITRO_PROVIDER_ID, ProviderRouting.TOWERX_PROVIDER_ID)
        return if (known.any { it !in excludedProviderIds }) AskTarget.Remote else AskTarget.Local
    }

    /** Add or remove [id] from the excluded set; a pinned provider that is excluded is unpinned. */
    fun exclude(id: String, exclude: Boolean): AskSettings {
        val next = if (exclude) excludedProviderIds + id else excludedProviderIds - id
        return copy(excludedProviderIds = next, pinnedProviderId = if (pinnedProviderId in next) null else pinnedProviderId)
    }

    companion object {
        /** Stored `code,vault` → set; blank or unknown values fall back to vault only. */
        fun parseSkills(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it == Skills.VAULT || it == Skills.CODE }.toSet().ifEmpty { setOf(Skills.VAULT) }

        fun parseIds(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

        fun encodeIds(ids: Set<String>): String = ids.sorted().joinToString(",")

        /**
         * One-time migration of the old Settings "Use Nitro/TowerX for Ask" flags. [stored] is the raw
         * `excluded_providers` preference: `null` means it has not been written yet, so a `false` flag becomes the
         * matching Settings provider id (`nitro5-qwen` / `towerx-qwen`). A present value (even blank) is kept as is.
         */
        fun migrateExcluded(stored: String?, nitroEnabled: Boolean = true, towerxEnabled: Boolean = true): Set<String> =
            if (stored != null) parseIds(stored) else buildSet {
                if (!nitroEnabled) add(ProviderRouting.NITRO_PROVIDER_ID)
                if (!towerxEnabled) add(ProviderRouting.TOWERX_PROVIDER_ID)
            }

        /** Trims, adds `http://` when no scheme is typed, drops trailing slashes and a trailing `/v1`. Blank stays blank. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return ""
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

    private fun load(): AskSettings {
        val first = !prefs.contains(KEY_EXCLUDED)
        val excluded = AskSettings.migrateExcluded(
            if (first) null else prefs.getString(KEY_EXCLUDED, ""),
            prefs.getBoolean(KEY_NITRO, true),
            prefs.getBoolean(KEY_TOWERX, true),
        )
        if (first) {
            // Write the new key once so a later load does not remigrate; clear the old flags so a wiped key is a no-op.
            prefs.edit()
                .putString(KEY_EXCLUDED, AskSettings.encodeIds(excluded))
                .putBoolean(KEY_NITRO, true)
                .putBoolean(KEY_TOWERX, true)
                .apply()
        }
        val pin = prefs.getString(KEY_PIN, null)?.takeIf { it.isNotBlank() }?.takeUnless { it in excluded }
        return AskSettings(
            askEnabled = prefs.getBoolean(KEY_ASK, true),
            askMode = prefs.getBoolean(KEY_MODE, false),
            pinnedProviderId = pin,
            skills = AskSettings.parseSkills(prefs.getString(KEY_SKILLS, null)),
            excludedProviderIds = excluded,
        )
    }

    fun update(transform: (AskSettings) -> AskSettings) {
        val next = transform(flow.value).let {
            val pin = it.pinnedProviderId?.takeUnless { id -> id in it.excludedProviderIds }
            it.copy(pinnedProviderId = pin)
        }
        prefs.edit()
            .putBoolean(KEY_ASK, next.askEnabled)
            .remove(KEY_GROUND) // retired: Ask no longer sends local hits to Nitro
            // Old URL prefs (KEY_URL, KEY_TOWERX_URL) are left in place and never read.
            .putBoolean(KEY_MODE, next.askMode)
            .putString(KEY_PIN, next.pinnedProviderId)
            .putString(KEY_SKILLS, next.skills.sorted().joinToString(","))
            .putString(KEY_EXCLUDED, AskSettings.encodeIds(next.excludedProviderIds))
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
        const val KEY_EXCLUDED = "excluded_providers"
    }
}
