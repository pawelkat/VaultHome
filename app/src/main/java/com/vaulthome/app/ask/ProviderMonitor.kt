package com.vaulthome.app.ask

import com.vaulthome.events.QueryReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** Live view for the Providers panel and Ask routing. */
data class ProviderState(
    /** A Zenoh session is running (event sync on). */
    val session: Boolean = false,
    /** `vault/presence/<device>` tokens seen: device -> alive. A device never seen is simply absent (unknown). */
    val alive: Map<String, Boolean> = emptyMap(),
    /** Last good caps document per device (live Zenoh, cache, or HTTP fallback). */
    val manifests: Map<String, List<Provider>> = emptyMap(),
    /** Last caps problem per device (no reply, bad JSON, unsupported schema). */
    val errors: Map<String, String> = emptyMap(),
    /** Exact raw payload and source metadata per device, for the caps JSON sheet. */
    val evidence: Map<String, CapsEvidence> = emptyMap(),
)

/**
 * Follows presence tokens and resolves capabilities in this order:
 *  1. live Zenoh query on `vault/caps/<device>`, then the caps wildcard (device from `hostedOn.deviceId`)
 *  2. last-known cached caps (timestamp kept, marked stale) — shown immediately
 *  3. if there is no cache, or the device is still alive, HTTP `GET {base}/caps` then `/health`+`/props`
 *  4. otherwise [ProviderRouting.fromRegistry] fills the gap (devices.json / derived URLs)
 *
 * A leave or a session close no longer drops last-known caps; they stay, marked stale.
 * The event sync service feeds [onToken] from a wildcard liveliness subscription and sets [querier]
 * (preferred) or [caller] while its Zenoh session runs.
 */
object ProviderMonitor {
    private val mutable = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = mutable

    /** `(selector, payload, timeoutMs) -> reply`, set while a Zenoh session runs. Used when [querier] is unset. */
    @Volatile var caller: ((String, String?, Long) -> String?)? = null

    /** `(selector, payload, timeoutMs) -> replies with keyexpr`. Preferred: the reply key may be the query selector. */
    @Volatile var querier: ((String, String?, Long) -> List<QueryReply>)? = null

    /** This phone's presence id: its own caps are not queried. */
    @Volatile var selfDevice: String = ""

    @Volatile var store: CapsStore = MemoryCapsStore()

    @Volatile var http: CapsHttp? = null

    /** Chat-proxy bases to try for HTTP fallback (tailnet first, then lan if listed). */
    @Volatile var bases: (String) -> List<String> = { emptyList() }

    /** Presence-triggered refresh; tests turn this off and call [refresh] themselves. */
    @Volatile var autoRefresh: Boolean = true

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "provider-caps").apply { isDaemon = true } }

    fun alive(device: String): Boolean? = mutable.value.alive[device]

    /** Presence prefix without the trailing `*`, e.g. `vault/presence/`. */
    private const val PREFIX = "vault/presence/"
    private const val ZENOH_TIMEOUT_MS = 5_000L
    private const val HTTP_TIMEOUT_MS = 5_000

    fun deviceOf(key: String): String? = key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() && it != "*" && '/' !in it }

    /** Wire persistence and HTTP, then load last-known caps as stale so the UI has them before Zenoh connects. */
    fun bind(store: CapsStore, http: CapsHttp? = UrlCapsHttp, bases: (String) -> List<String> = { emptyList() }) {
        this.store = store
        this.http = http
        this.bases = bases
        restoreCache()
    }

    fun restoreCache() {
        val loaded = store.all().mapNotNull { rec ->
            ProviderManifest.forDevice(rec.json, rec.device)?.let { list ->
                Triple(rec.device, list.stamped(rec.source, rec.atMs, stale = true), CapsEvidence.from(rec, stale = true))
            }
        }
        if (loaded.isEmpty()) return
        mutable.update { s ->
            val keep = s.manifests.filterValues { it.any { p -> !p.capsStale } }
            val keepEv = s.evidence.filterValues { !it.stale }
            s.copy(
                manifests = loaded.associate { it.first to it.second } + keep,
                evidence = loaded.associate { it.first to it.third } + keepEv,
            )
        }
    }

    /** Presence transport thread: never blocks; the caps query runs on [io]. */
    fun onToken(key: String, alive: Boolean) {
        if (key == "vault/presence/*") { if (!alive) onSessionClosed(); return }
        val device = deviceOf(key) ?: return
        mutable.update { s ->
            s.copy(
                session = true,
                alive = s.alive + (device to alive),
                manifests = if (alive) s.manifests else markStale(s.manifests, device),
                evidence = if (alive) s.evidence else markEvidenceStale(s.evidence, device),
            )
        }
        if (autoRefresh && alive && device != selfDevice) io.execute { refresh(device) }
    }

    /**
     * Resolve caps for [device] now (also for a manual refresh); blocking, call off the main thread.
     * See the class KDoc for the fallback chain.
     */
    fun refresh(device: String, now: Long = System.currentTimeMillis()) {
        val live = mutable.value.alive[device] == true
        // Identity is always hostedOn.deviceId, never the reply keyexpr (which may be the query selector).
        val specific = zenohReplies(ProviderManifest.key(device))
        applyAllFrom(specific, now)
        if (pickForDevice(specific, device) != null) return
        val wild = zenohReplies(ProviderManifest.WILDCARD)
        applyAllFrom(wild, now)
        if (pickForDevice(wild, device) != null) return
        fallbackAfterZenoh(device, live, now, if (querier == null && caller == null) "no Zenoh session" else "no caps reply")
    }

    /** GET `vault/caps/<device>`, then the caps wildcard if that missed or the body was not for [device]. */
    internal fun fetchZenoh(device: String): QueryReply? {
        pickForDevice(zenohReplies(ProviderManifest.key(device)), device)?.let { return it }
        return pickForDevice(zenohReplies(ProviderManifest.WILDCARD), device)
    }

    private fun zenohReplies(selector: String): List<QueryReply> {
        querier?.let { q -> return runCatching { q(selector, null, ZENOH_TIMEOUT_MS) }.getOrDefault(emptyList()) }
        val text = caller?.let { c -> runCatching { c(selector, null, ZENOH_TIMEOUT_MS) }.getOrNull() } ?: return emptyList()
        return listOf(QueryReply(selector, text))
    }

    internal fun pickForDevice(replies: List<QueryReply>, device: String): QueryReply? =
        replies.firstOrNull { ProviderManifest.forDevice(it.payload, device) != null }

    private fun applyAllFrom(replies: List<QueryReply>, now: Long) {
        for (hit in replies) {
            val ids = ProviderManifest.parse(hit.payload).getOrNull()?.map { it.device }?.toSet() ?: continue
            for (id in ids) {
                if (id.isNotBlank() && id != selfDevice) {
                    apply(id, hit.payload, CapsSource.Zenoh, now, stale = mutable.value.alive[id] == false, error = null, zenohKey = hit.key)
                }
            }
        }
    }

    private fun fallbackAfterZenoh(device: String, live: Boolean, now: Long, zenohError: String) {
        val cached = store.get(device)?.takeIf { ProviderManifest.forDevice(it.json, it.device) != null }
        if (cached != null) {
            apply(
                device, cached.json, cached.source, cached.atMs, stale = true,
                error = "$zenohError; using last-known caps",
                zenohKey = cached.zenohKey, healthJson = cached.healthJson, propsJson = cached.propsJson,
                registryJson = cached.registryJson,
            )
            if (live) tryHttp(device, now, keepOnMiss = true)
            return
        }
        if (!tryHttp(device, now, keepOnMiss = false)) {
            mutable.update { s -> s.copy(errors = s.errors + (device to zenohError)) }
        }
    }

    /** @return true when a live HTTP document was applied. */
    private fun tryHttp(device: String, now: Long, keepOnMiss: Boolean): Boolean {
        val client = http ?: return false
        val urls = bases(device).filter { it.isNotBlank() }
        if (urls.isEmpty()) return false
        val errors = mutableListOf<String>()
        for (base in urls) {
            val root = base.trimEnd('/')
            for (path in listOf("/caps", "/caps.json")) {
                val got = runCatching { client.get("$root$path", HTTP_TIMEOUT_MS) }.getOrNull() ?: continue
                val (code, body) = got
                if (code == 404) continue
                if (code !in 200..299 || body.isBlank()) {
                    errors += "$path HTTP $code"
                    continue
                }
                if (ProviderManifest.forDevice(body, device) != null) {
                    val stale = mutable.value.alive[device] == false
                    apply(device, body, CapsSource.HttpCaps, now, stale, error = null)
                    return true
                }
                errors += "$path: ${ProviderManifest.parse(body).exceptionOrNull()?.message ?: "not for $device"}"
            }
        }
        for (base in urls) {
            val root = base.trimEnd('/')
            val health = runCatching { client.get("$root/health", HTTP_TIMEOUT_MS) }.getOrNull()
            val props = runCatching { client.get("$root/props", HTTP_TIMEOUT_MS) }.getOrNull()
            val healthOk = health != null && CapsFromProbe.healthOk(health.first, health.second)
            val nCtx = props?.takeIf { it.first in 200..299 }?.let { CapsFromProbe.nCtxFromProps(it.second) }
            if (healthOk || nCtx != null) {
                val json = CapsFromProbe.syntheticV2(device, root, nCtx, ready = healthOk)
                val stale = mutable.value.alive[device] == false
                val note = if (!healthOk) " /health not ok, nCtx from /props" else null
                apply(
                    device, json, CapsSource.HealthProps, now, stale, error = note,
                    healthJson = health?.second, propsJson = props?.takeIf { it.first in 200..299 }?.second,
                )
                return true
            }
            if (health == null) errors += "/health unreachable at $root"
            else errors += "/health HTTP ${health.first}"
        }
        if (!keepOnMiss && errors.isNotEmpty()) {
            mutable.update { s -> s.copy(errors = s.errors + (device to errors.first())) }
        }
        return false
    }

    private fun apply(
        device: String,
        json: String,
        source: CapsSource,
        atMs: Long,
        stale: Boolean,
        error: String?,
        zenohKey: String? = null,
        healthJson: String? = null,
        propsJson: String? = null,
        registryJson: String? = null,
    ) {
        val list = ProviderManifest.forDevice(json, device)?.stamped(source, atMs, stale) ?: return
        val ev = CapsEvidence(device, json, source, atMs, stale, zenohKey, healthJson, propsJson, registryJson)
        if (!stale) ev.toRecord()?.let(store::put)
        mutable.update { s ->
            s.copy(
                manifests = s.manifests + (device to list),
                evidence = s.evidence + (device to ev),
                errors = if (error == null) s.errors - device else s.errors + (device to error),
            )
        }
    }

    private fun markStale(manifests: Map<String, List<Provider>>, device: String): Map<String, List<Provider>> {
        val list = manifests[device] ?: return manifests
        return manifests + (device to list.map { it.copy(capsStale = true) })
    }

    private fun markEvidenceStale(evidence: Map<String, CapsEvidence>, device: String): Map<String, CapsEvidence> {
        val ev = evidence[device] ?: return evidence
        return evidence + (device to ev.copy(stale = true))
    }

    fun onSessionStarted() = mutable.update { it.copy(session = true) }

    /** Session closed: presence is unknown; last-known caps stay, marked stale. */
    fun onSessionClosed() {
        mutable.update { s ->
            s.copy(
                session = false,
                alive = emptyMap(),
                manifests = s.manifests.mapValues { (_, list) -> list.map { it.copy(capsStale = true) } },
                evidence = s.evidence.mapValues { (_, ev) -> ev.copy(stale = true) },
            )
        }
    }

    /** Evidence for the JSON sheet: live/cache/HTTP record, or the devices.json entry as a last resort. */
    fun evidenceFor(device: String, registry: List<com.vaulthome.app.devices.RegDevice> = emptyList()): CapsEvidence? {
        mutable.value.evidence[device]?.let { return it }
        val d = registry.firstOrNull { it.id == device } ?: return null
        val entry = com.vaulthome.app.devices.DeviceRegistryJson.encodeOne(d)
        return CapsEvidence(device, entry, CapsSource.Registry, atMs = null, registryJson = entry)
    }

    /** Tests: drop in-memory state and use a fresh memory store (does not touch app prefs). */
    fun resetForTests() {
        caller = null
        querier = null
        http = null
        bases = { emptyList() }
        store = MemoryCapsStore()
        selfDevice = ""
        autoRefresh = false
        mutable.value = ProviderState()
    }
}
