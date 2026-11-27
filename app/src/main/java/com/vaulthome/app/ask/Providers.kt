package com.vaulthome.app.ask

import org.json.JSONArray
import org.json.JSONObject

/** Skill names in caps v2 (ontology local names). Settings still accept the old `vault`/`code` aliases. */
object Skills {
    const val VAULT = "VaultSkill"
    const val CODE = "CodeSkill"
    const val SEARCH = "SearchSkill"

    /** Maps a caps or settings token to a v2 skill name; unknown tokens are dropped. */
    fun normalize(raw: String): String? = when (raw.trim()) {
        VAULT, "vault" -> VAULT
        CODE, "code" -> CODE
        SEARCH, "search" -> SEARCH
        else -> null
    }

    /** Ask settings only store vault and code; search is an index skill, not a chat request skill. */
    fun normalizeAsk(raw: String): String? = normalize(raw)?.takeIf { it == VAULT || it == CODE }
}

/**
 * One entry of a device's `vault/caps/<device>` manifest (caps v2 JSON-LD). Nothing here names a preferred
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
    /**
     * Optional helper-only field: URL prefix of vault file links this provider emits
     * (e.g. `https://towerx.tailf52603.ts.net/vault/`). Not in the ontology; ignored if absent.
     */
    val linkBase: String? = null,
    /** Where these capabilities came from; [CapsSource.Registry] when synthesised with [fromSettings]. */
    val capsSource: CapsSource = CapsSource.Registry,
    /** Wall-clock millis when [capsSource] last fetched this document; null for static registry defaults. */
    val capsAtMs: Long? = null,
    /** True when this is last-known caps (device left, session down, or Zenoh missed and cache was used). */
    val capsStale: Boolean = false,
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
        const val KIND_CHAT = "LlmChatProvider"
        const val KIND_EMBEDDING = "EmbeddingIndexProvider"
        const val TRANSPORT_PROXY = "ChatProxy"
        const val TRANSPORT_INDEX = "LocalIndex"
        const val SCOPE_TAILNET = "Tailnet"
        const val SCOPE_LOCAL = "Local"
        const val ACCESS_READ_ONLY = "ReadOnly"
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/** Parses caps v2 JSON-LD on `vault/caps/<device>`. The `@context` URL is ignored (bundle a local copy; it does not resolve). Unknown fields are ignored; a bad or v1 schema is an error, not a guess. */
object ProviderManifest {
    const val SCHEMA = 2

    fun key(device: String) = "vault/caps/$device"

    fun parse(json: String): Result<List<Provider>> = runCatching {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", -1) == SCHEMA) { "unsupported caps schemaVersion ${root.opt("schemaVersion")}" }
        val arr = root.optJSONArray("@graph") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { provider(it) } }
    }

    private fun provider(o: JSONObject): Provider? {
        val id = localName(stringOrFirst(o, "@id")).takeIf { it.isNotBlank() } ?: return null
        val kind = localName(stringOrFirst(o, "@type")).takeIf { it.isNotBlank() } ?: return null
        val hosted = o.optJSONObject("hostedOn")
        val device = hosted?.optString("deviceId")?.takeIf { it.isNotBlank() } ?: return null
        return Provider(
            id = id,
            device = device,
            kind = kind,
            title = o.optString("label").ifBlank { id },
            description = o.optString("comment"),
            skills = strings(o.optJSONArray("providesSkill")).map(::localName),
            endpoint = o.optString("endpointUrl"),
            transport = localName(o.optString("transport")),
            scope = localName(o.optString("scope")),
            access = localName(o.optString("accessMode")),
            ready = o.optBoolean("ready", false),
            nCtx = positive(o, "nCtx"),
            maxTokensSuggest = positive(o, "maxTokensSuggest"),
            linkBase = o.optString("linkBase").ifBlank { null },
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    private fun positive(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.optInt(key, 0).takeIf { it > 0 }

    /** Compact name, or the last path / fragment segment of an IRI. */
    internal fun localName(raw: String): String {
        val s = raw.trim()
        val cut = maxOf(s.lastIndexOf('#'), s.lastIndexOf('/'))
        return if (cut >= 0 && cut < s.length - 1) s.substring(cut + 1) else s
    }

    private fun stringOrFirst(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        val v = o.get(key)
        return when (v) {
            is JSONArray -> if (v.length() == 0) "" else v.optString(0)
            else -> v.toString()
        }
    }
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
    val local = AvailabilitySource { p -> if (p.scope == Provider.SCOPE_LOCAL) Availability.Available else null }

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
 *  2. Otherwise the auto-pick rule from the brief: the available `nitro5` LlmChatProvider with only the `VaultSkill`.
 *  3. Then every other available LlmChatProvider, by id, so the order is stable and not tied to a host.
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
            transport = Provider.TRANSPORT_PROXY, scope = Provider.SCOPE_TAILNET, access = Provider.ACCESS_READ_ONLY,
            ready = true, nCtx = null, maxTokensSuggest = null, fromSettings = true, lanUrl = urls.lan,
            capsSource = CapsSource.Registry, capsAtMs = null, capsStale = false,
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
            description = "On-device embedding search only", skills = listOf(Skills.SEARCH), endpoint = "", transport = Provider.TRANSPORT_INDEX,
            scope = Provider.SCOPE_LOCAL, access = Provider.ACCESS_READ_ONLY, ready = true, nCtx = null, maxTokensSuggest = null,
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

    /**
     * Request sizing. [Provider.maxTokensSuggest] always wins when present.
     * If `nCtx` is unknown and no suggest is given, only the nitro5 provider gets today's 1024 cap (and the UI note);
     * every other provider omits `max_tokens` so the server decides.
     */
    fun options(p: Provider, wanted: Set<String>): Pair<ChatOptions, String?> {
        val skills = if (wanted == setOf(Skills.VAULT)) null else wanted.sorted()
        val appliedFallback = p.maxTokensSuggest == null && p.nCtx == null && p.device == NITRO_DEVICE
        val max = p.maxTokensSuggest ?: if (appliedFallback) Provider.DEFAULT_MAX_TOKENS else null
        val note = if (appliedFallback) "nCtx unknown: using ${Provider.DEFAULT_MAX_TOKENS} tokens" else null
        return ChatOptions(max, skills) to note
    }
}
