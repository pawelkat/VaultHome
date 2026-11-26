package com.vaulthome.app.ask

import org.json.JSONArray
import org.json.JSONObject

/** Skill names in the proposed caps schema v1 (vault note `Koder - VaultHome providers panel`). */
object Skills {
    const val VAULT = "vault"
    const val CODE = "code"
    const val SEARCH = "search"
}

/**
 * One entry of a device's `vault/caps/<device>` manifest (proposed schema v1). Nothing here names a preferred
 * host: ordering lives in [ProviderRouting].
 */
data class Provider(
    val id: String,
    val device: String,
    val kind: String,
    val title: String,
    val description: String,
    val skills: List<String>,
    val endpoint: String,
    val transport: String,
    val scope: String,
    val access: String,
    val ready: Boolean,
    val nCtx: Int?,
    val maxTokensSuggest: Int?,
    /** True when synthesised from Settings URLs (no manifest seen); readiness is then decided by `/health`. */
    val fromSettings: Boolean = false,
) {
    val isChat: Boolean get() = kind == KIND_CHAT && transport == TRANSPORT_PROXY

    /** Chat proxy root: the manifest endpoint without `/v1/chat/completions`. */
    val baseUrl: String get() = AskSettings.normalizeBaseUrl(endpoint.removeSuffix("/").removeSuffix("/chat/completions"))

    /** Short host name for badges and the answer header. */
    val hostLabel: String get() = when (device) {
        "nitro5" -> "Nitro"
        "towerx" -> "TowerX"
        else -> device.replaceFirstChar { it.uppercase() }
    }

    fun hasSkills(wanted: Set<String>): Boolean = skills.containsAll(wanted)

    companion object {
        const val KIND_CHAT = "llm-chat"
        const val KIND_EMBEDDING = "embedding-index"
        const val TRANSPORT_PROXY = "chat-proxy"
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/** Parses `vault/caps/<device>` JSON. Unknown fields are ignored; a bad or newer schema is an error, not a guess. */
object ProviderManifest {
    const val SCHEMA = 1

    fun key(device: String) = "vault/caps/$device"

    fun parse(json: String): Result<List<Provider>> = runCatching {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", -1) == SCHEMA) { "unsupported caps schemaVersion ${root.opt("schemaVersion")}" }
        val device = root.optString("device").also { require(it.isNotBlank()) { "caps without device" } }
        val arr = root.optJSONArray("providers") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { provider(device, it) } }
    }

    private fun provider(device: String, o: JSONObject): Provider? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val kind = o.optString("kind").takeIf { it.isNotBlank() } ?: return null
        return Provider(
            id = id,
            device = device,
            kind = kind,
            title = o.optString("title").ifBlank { id },
            description = o.optString("description"),
            skills = strings(o.optJSONArray("skills")),
            endpoint = o.optString("endpoint"),
            transport = o.optString("transport"),
            scope = o.optString("scope"),
            access = o.optString("access"),
            ready = o.optBoolean("ready", false),
            nCtx = positive(o, "n_ctx"),
            maxTokensSuggest = positive(o, "max_tokens_suggest"),
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    private fun positive(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.optInt(key, 0).takeIf { it > 0 }
}

/** Why a provider is not in the try-list. */
data class Skipped(val provider: Provider, val reason: String)

/** Ordered chat providers to try, plus the ones left out and why. */
data class Routing(val order: List<Provider>, val skipped: List<Skipped>)

/**
 * Provider choice without a baked-in host order (note: "do not prefer any LLM provider").
 *  1. A provider the user pinned in the panel goes first.
 *  2. Otherwise the auto-pick rule from the brief: the ready `nitro5` llm-chat provider with only the `vault` skill.
 *  3. Then every other ready llm-chat provider, by id, so the order is stable and not tied to a host.
 * A provider is left out when it is disabled in Settings, its manifest says `ready=false`, its Zenoh agent is known
 * offline, or it lacks a skill the user asked for. Nothing is assumed up: `/health` still runs before each chat.
 */
object ProviderRouting {
    /** Auto-pick rule from the brief; the panel's pin overrides it. */
    const val AUTO_PICK_DEVICE = "nitro5"
    const val NITRO_DEVICE = "nitro5"
    const val TOWERX_DEVICE = "towerx"

    /** Providers from the Settings URLs: used for a device until a manifest for it arrives. */
    fun fromSettings(s: AskSettings): List<Provider> = buildList {
        if (s.nitroEnabled) add(legacy(NITRO_DEVICE, "nitro5-qwen", "Nitro chat proxy", s.nitroBaseUrl))
        if (s.towerxEnabled) add(legacy(TOWERX_DEVICE, "towerx-qwen", "TowerX chat proxy", s.towerxBaseUrl))
    }

    private fun legacy(device: String, id: String, title: String, url: String) = Provider(
        id = id, device = device, kind = Provider.KIND_CHAT, title = title, description = "From Settings (no caps manifest yet)",
        skills = listOf(Skills.VAULT), endpoint = "$url/v1/chat/completions", transport = Provider.TRANSPORT_PROXY,
        scope = "tailnet", access = "read-only", ready = true, nCtx = null, maxTokensSuggest = null, fromSettings = true,
    )

    /** Manifest providers win per device; Settings fill devices without a manifest; the on-device index is always listed. */
    fun all(manifests: Map<String, List<Provider>>, s: AskSettings, localDevice: String = "s24"): List<Provider> {
        val settings = fromSettings(s).filter { it.device !in manifests }
        val remote = manifests.filterKeys { enabled(it, s) }.values.flatten()
        val localGemma = Provider(
            id = "$localDevice-gemma", device = localDevice, kind = Provider.KIND_EMBEDDING, title = "Local EmbeddingGemma index",
            description = "On-device embedding search only", skills = listOf(Skills.SEARCH), endpoint = "local", transport = "local-index",
            scope = "local", access = "read-only", ready = true, nCtx = null, maxTokensSuggest = null,
        )
        return (remote + settings).filter { it.device != localDevice || it.kind != Provider.KIND_EMBEDDING } + localGemma
    }

    /** Settings switches stay as per-host off switches ("don't use this host"), not as an order. */
    private fun enabled(device: String, s: AskSettings) = when (device) {
        NITRO_DEVICE -> s.nitroEnabled
        TOWERX_DEVICE -> s.towerxEnabled
        else -> true
    }

    fun route(
        providers: List<Provider>,
        pinnedId: String?,
        wanted: Set<String> = setOf(Skills.VAULT),
        deviceAlive: (String) -> Boolean? = { null },
    ): Routing {
        val skipped = mutableListOf<Skipped>()
        val usable = providers.filter { it.isChat }.filter { p ->
            val why = when {
                !p.ready -> "not ready"
                deviceAlive(p.device) == false -> "offline in Zenoh"
                !p.hasSkills(wanted) -> "no ${(wanted - p.skills.toSet()).joinToString("/")} skill"
                else -> null
            }
            if (why != null) skipped += Skipped(p, why)
            why == null
        }.sortedBy { it.id }
        val first = usable.firstOrNull { it.id == pinnedId }
            ?: usable.firstOrNull { it.device == AUTO_PICK_DEVICE && it.skills == listOf(Skills.VAULT) }
        val order = if (first == null) usable else listOf(first) + usable.filter { it !== first }
        return Routing(order, skipped)
    }

    /** `max_tokens` from the live manifest, else today's 1024. [note] is non-null when n_ctx was missing (shown in the UI). */
    fun options(p: Provider, wanted: Set<String>): Pair<ChatOptions, String?> {
        val max = if (p.nCtx == null) Provider.DEFAULT_MAX_TOKENS else p.maxTokensSuggest ?: Provider.DEFAULT_MAX_TOKENS
        val skills = if (wanted == setOf(Skills.VAULT)) null else wanted.sorted()
        val note = if (p.nCtx == null) "n_ctx unknown: using $max tokens" else null
        return ChatOptions(max, skills) to note
    }
}
