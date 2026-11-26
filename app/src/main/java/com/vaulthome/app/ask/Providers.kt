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
    /** True when synthesised from the registry / derived tailnet URL (no manifest seen); readiness is then decided by `/health`. */
    val fromSettings: Boolean = false,
    /** LAN chat-proxy URL from devices.json `endpoints.lan`, if any; never a hardcoded address. Tried after [baseUrl]. */
    val lanUrl: String? = null,
) {
    val isChat: Boolean get() = kind == KIND_CHAT && transport == TRANSPORT_PROXY

    /** Chat proxy root: the manifest endpoint without `/v1/chat/completions`. */
    val baseUrl: String get() = AskSettings.normalizeBaseUrl(endpoint.removeSuffix("/").removeSuffix("/chat/completions"))

    /** URLs to probe and call: tailnet/manifest first, then a registry lan endpoint if one was listed. */
    fun urls(): List<String> = listOf(baseUrl) + listOfNotNull(lanUrl?.takeIf { it != baseUrl })

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

/** Availability of one provider right now. [routable] ones are tried by Ask (Unknown still gets a `/health` check). */
enum class Availability(val label: String, val routable: Boolean) {
    Available("available", true),
    Unknown("unknown", true),
    Offline("offline", false),
    Disabled("disabled", false),
    NoSkill("no skill", false),
}

/** Last `/health` result for a device, with the time it was taken. */
data class HealthSample(val ok: Boolean, val detail: String, val atMs: Long)

/**
 * Pluggable availability source: `null` = no opinion, so the next source decides. Temporary S24 sources are
 * hardcoded below (per-provider "Don't use for Ask", TowerX/Nitro5 presence + `/health`); a Zenoh caps manifest overrides them.
 */
fun interface AvailabilitySource {
    fun availability(p: Provider): Availability?
}

object ProviderAvailability {
    /** A health result older than this says nothing any more. */
    const val HEALTH_TTL_MS = 60_000L

    /** On-device providers need nothing from the network. */
    val local = AvailabilitySource { p -> if (p.scope == "local") Availability.Available else null }

    /** Providers the user turned off with "Don't use for Ask" (keyed by provider id). */
    fun settingsSwitch(s: AskSettings) = AvailabilitySource { p ->
        if (p.id in s.excludedProviderIds) Availability.Disabled else null
    }

    /** Zenoh presence token `vault/presence/<device>`: a token known gone means offline; alive says nothing more. */
    fun presence(alive: (String) -> Boolean?) = AvailabilitySource { p -> if (alive(p.device) == false) Availability.Offline else null }

    /** The device's own caps manifest (`ready`) overrides the temporary probes below it. */
    val manifest = AvailabilitySource { p -> if (p.fromSettings) null else if (p.ready) Availability.Available else Availability.Offline }

    /** Temporary S24 logic: the last `/health` probe (TowerX and Nitro5 alike), if still fresh. */
    fun health(sample: (String) -> HealthSample?, now: () -> Long) = AvailabilitySource { p ->
        sample(p.device)?.takeIf { now() - it.atMs <= HEALTH_TTL_MS }?.let { if (it.ok) Availability.Available else Availability.Offline }
    }

    /** Standard chain, strongest first. */
    fun chain(
        s: AskSettings,
        alive: (String) -> Boolean? = { null },
        sample: (String) -> HealthSample? = { null },
        now: () -> Long = System::currentTimeMillis,
    ): List<AvailabilitySource> = listOf(local, settingsSwitch(s), presence(alive), manifest, health(sample, now))

    fun of(p: Provider, wanted: Set<String>, sources: List<AvailabilitySource>): Availability {
        val base = sources.firstNotNullOfOrNull { it.availability(p) } ?: Availability.Unknown
        return if (base.routable && p.kind == Provider.KIND_CHAT && !p.hasSkills(wanted)) Availability.NoSkill else base
    }
}

/** Why a provider is not in the try-list. */
data class Skipped(val provider: Provider, val reason: String)

/** Ordered chat providers to try, plus the ones left out and why. */
data class Routing(val order: List<Provider>, val skipped: List<Skipped>)

/**
 * Provider choice without a baked-in host order (note: "do not prefer any LLM provider").
 *  1. A provider the user pinned in the panel goes first.
 *  2. Otherwise the auto-pick rule from the brief: the available `nitro5` llm-chat provider with only the `vault` skill.
 *  3. Then every other available llm-chat provider, by id, so the order is stable and not tied to a host.
 * Providers whose [Availability] is not routable (offline, disabled, no skill) are never tried; a pinned provider that
 * goes offline is simply skipped. Unknown ones are tried, since `/health` still runs before each chat.
 */
object ProviderRouting {
    /** Auto-pick rule from the brief; the panel's pin overrides it. */
    const val AUTO_PICK_DEVICE = "nitro5"
    const val NITRO_DEVICE = "nitro5"
    const val TOWERX_DEVICE = "towerx"
    const val NITRO_PROVIDER_ID = "nitro5-qwen"
    const val TOWERX_PROVIDER_ID = "towerx-qwen"

    /**
     * Chat providers from [devices] (kind `pc`, a chat_proxy, or lan/tailnet endpoints) until a caps manifest
     * arrives for that device. URLs come from [DeviceUrls], not from stored Settings prefs.
     */
    fun fromRegistry(
        devices: List<com.vaulthome.app.devices.RegDevice>,
        suffix: String = TailnetUrls.FALLBACK_SUFFIX,
    ): List<Provider> = devices.filter(DeviceUrls::isChatHost).map { d ->
        val urls = DeviceUrls.resolve(d.id, suffix, d)
        val title = if (d.id == NITRO_DEVICE) "Nitro chat proxy" else if (d.id == TOWERX_DEVICE) "TowerX chat proxy" else "${d.name} chat proxy"
        val id = when (d.id) {
            NITRO_DEVICE -> NITRO_PROVIDER_ID
            TOWERX_DEVICE -> TOWERX_PROVIDER_ID
            else -> "${d.id}-qwen"
        }
        Provider(
            id = id, device = d.id, kind = Provider.KIND_CHAT, title = title,
            description = "From ${urls.source.name.lowercase()} URL (no caps manifest yet)",
            skills = listOf(Skills.VAULT), endpoint = "${urls.primary}/v1/chat/completions",
            transport = Provider.TRANSPORT_PROXY, scope = "tailnet", access = "read-only",
            ready = true, nCtx = null, maxTokensSuggest = null, fromSettings = true, lanUrl = urls.lan,
        )
    }

    /** Manifest providers win per device; the registry fills devices without a manifest; the on-device index is always listed. */
    fun all(
        manifests: Map<String, List<Provider>>,
        s: AskSettings = AskSettings(),
        localDevice: String = "s24",
        registry: List<com.vaulthome.app.devices.RegDevice> = com.vaulthome.app.devices.DeviceRegistryJson.bundled().devices,
        suffix: String = TailnetUrls.FALLBACK_SUFFIX,
    ): List<Provider> {
        val settings = fromRegistry(registry, suffix).filter { it.device !in manifests }
        val localGemma = Provider(
            id = "$localDevice-gemma", device = localDevice, kind = Provider.KIND_EMBEDDING, title = "Local EmbeddingGemma index",
            description = "On-device embedding search only", skills = listOf(Skills.SEARCH), endpoint = "local", transport = "local-index",
            scope = "local", access = "read-only", ready = true, nCtx = null, maxTokensSuggest = null,
        )
        return (manifests.values.flatten() + settings).filter { it.device != localDevice || it.kind != Provider.KIND_EMBEDDING } + localGemma
    }

    fun route(
        providers: List<Provider>,
        pinnedId: String?,
        wanted: Set<String> = setOf(Skills.VAULT),
        availability: (Provider) -> Availability = { Availability.Unknown },
    ): Routing {
        val skipped = mutableListOf<Skipped>()
        val usable = providers.filter { it.isChat }.filter { p ->
            val a = availability(p)
            if (!a.routable) skipped += Skipped(p, if (a == Availability.NoSkill) "no ${(wanted - p.skills.toSet()).joinToString("/")} skill" else a.label)
            a.routable
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
