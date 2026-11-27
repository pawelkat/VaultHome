package com.vaulthome.app.ask

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
)

/**
 * Follows presence tokens and resolves capabilities in this order:
 *  1. live Zenoh query on `vault/caps/<device>`
 *  2. last-known cached caps (timestamp kept, marked stale) — shown immediately
 *  3. if there is no cache, or the device is still alive, HTTP `GET {base}/caps` then `/health`+`/props`
 *  4. otherwise [ProviderRouting.fromRegistry] fills the gap (devices.json / derived URLs)
 *
 * A leave or a session close no longer drops last-known caps; they stay, marked stale.
 * Presence stays as it is and no other keys are used. The event sync service feeds [onToken]
 * from a wildcard liveliness subscription and sets [caller] while its Zenoh session runs.
 */
object ProviderMonitor {
    private val mutable = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = mutable

    /** `(selector, payload, timeoutMs) -> reply`, set while a Zenoh session runs. */
    @Volatile var caller: ((String, String?, Long) -> String?)? = null

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
            ProviderManifest.parse(rec.json).getOrNull()?.let { rec.device to it.stamped(rec.source, rec.atMs, stale = true) }
        }
        if (loaded.isEmpty()) return
        mutable.update { s ->
            // Live (non-stale) entries already in memory win over a stale restore.
            val keep = s.manifests.filterValues { it.any { p -> !p.capsStale } }
            s.copy(manifests = loaded.toMap() + keep)
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

        val zenoh = caller?.let { c -> runCatching { c(ProviderManifest.key(device), null, ZENOH_TIMEOUT_MS) }.getOrNull() }
        if (zenoh != null) {
            val parsed = ProviderManifest.parse(zenoh)
            parsed.fold(
                { apply(device, zenoh, CapsSource.Zenoh, now, stale = !live, error = null) },
                { e -> fallbackAfterZenoh(device, live, now, "Zenoh caps: ${e.message ?: "bad caps"}") },
            )
            return
        }
        fallbackAfterZenoh(device, live, now, if (caller == null) "no Zenoh session" else "no caps reply")
    }

    private fun fallbackAfterZenoh(device: String, live: Boolean, now: Long, zenohError: String) {
        val cached = store.get(device)?.takeIf { ProviderManifest.parse(it.json).isSuccess }
        if (cached != null) {
            apply(device, cached.json, cached.source, cached.atMs, stale = true, error = "$zenohError; using last-known caps")
            // A live device can still upgrade a stale cache via HTTP (new /caps, or /props nCtx).
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
                val parsed = ProviderManifest.parse(body)
                if (parsed.isSuccess) {
                    val stale = mutable.value.alive[device] == false
                    apply(device, body, CapsSource.HttpCaps, now, stale, error = null)
                    return true
                }
                errors += "$path: ${parsed.exceptionOrNull()?.message ?: "bad caps"}"
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
                apply(device, json, CapsSource.HealthProps, now, stale, error = note)
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

    private fun apply(device: String, json: String, source: CapsSource, atMs: Long, stale: Boolean, error: String?) {
        val list = ProviderManifest.parse(json).getOrNull()?.stamped(source, atMs, stale) ?: return
        if (!stale) store.put(CapsRecord(device, json, source, atMs))
        mutable.update { s ->
            s.copy(
                manifests = s.manifests + (device to list),
                errors = if (error == null) s.errors - device else s.errors + (device to error),
            )
        }
    }

    private fun markStale(manifests: Map<String, List<Provider>>, device: String): Map<String, List<Provider>> {
        val list = manifests[device] ?: return manifests
        return manifests + (device to list.map { it.copy(capsStale = true) })
    }

    fun onSessionStarted() = mutable.update { it.copy(session = true) }

    /** Session closed: presence is unknown; last-known caps stay, marked stale. */
    fun onSessionClosed() {
        mutable.update { s ->
            s.copy(
                session = false,
                alive = emptyMap(),
                manifests = s.manifests.mapValues { (_, list) -> list.map { it.copy(capsStale = true) } },
            )
        }
    }

    /** Tests: drop in-memory state and use a fresh memory store (does not touch app prefs). */
    fun resetForTests() {
        caller = null
        http = null
        bases = { emptyList() }
        store = MemoryCapsStore()
        selfDevice = ""
        autoRefresh = false
        mutable.value = ProviderState()
    }
}
