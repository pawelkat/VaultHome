package com.vaulthome.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** A host that can run a Zenoh router (or, with [dialable] false, one that only dials out). */
data class RouterEntry(
    val id: String,
    val endpoint: String? = null,
    val tailnetName: String? = null,
    val dialable: Boolean = true,
    val note: String? = null,
)

/** One device's self-description, written by that device. */
data class DeviceEntry(
    val device: String,
    val app: String,
    val mode: LinkMode,
    val listen: List<String> = emptyList(),
    /** How others dial this device (router/peer only), e.g. `tcp/pawels-s24-ultra.tailf52603.ts.net:7447`. */
    val endpoint: String? = null,
    val tailnetName: String? = null,
    val lastSeen: String? = null,
)

data class SharedConfig(val routers: List<RouterEntry>, val devices: Map<String, DeviceEntry>)

/**
 * Shared endpoint list in the vault, [PATH] (same `app_settings/<App>/` layout as GymMonth). It syncs
 * like any note, so every device sees the others' entries.
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "source": { "note": "..." },
 *   "routers": [ { "id": "note4", "endpoint": "tcp/note4.tailf52603.ts.net:7447", "dialable": true, "note": "..." },
 *                { "id": "box", "tailnetName": "grokbot-user-155710816.tailf52603.ts.net", "dialable": false, "note": "..." } ],
 *   "devices": { "s24": { "app": "VaultHome", "mode": "router", "listen": ["tcp/0.0.0.0:7447"],
 *                         "endpoint": "tcp/pawels-s24-ultra.tailf52603.ts.net:7447",
 *                         "tailnetName": "pawels-s24-ultra.tailf52603.ts.net", "lastSeen": "2026-11-18T17:00:00Z" } }
 * }
 * ```
 *
 * A device only ever replaces `devices.<its name>`; everything else (other devices, `routers`, unknown
 * keys) is kept as read. `routers` is written only when the file is seeded.
 */
object SharedEndpoints {
    const val PATH = "app_settings/VaultEvents/endpoints.json"
    const val SCHEMA_VERSION = 1

    /** Do not rewrite the file just to move `lastSeen` forward more often than this. */
    const val LAST_SEEN_REFRESH_MS: Long = 6 * 60 * 60 * 1000L

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val endpointRe = Regex("(?i)(tcp|udp|tls|quic|ws)/(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):([0-9]{1,5})")
    private val ipv4Labeled = Regex("""(?i)IPv4\s+(\d{1,3}(?:\.\d{1,3}){3})""")

    fun isEndpoint(e: String): Boolean = normalizeEndpoint(e) != null

    /**
     * Canonical `tcp/host:port` (lowercase proto and host, trailing-dot and `#`/`?` params stripped).
     * `null` if it is not a valid endpoint.
     */
    fun normalizeEndpoint(e: String): String? {
        val raw = e.trim().substringBefore('#').substringBefore('?')
        val m = endpointRe.matchEntire(raw) ?: return null
        val port = m.groupValues[3].toInt()
        if (port !in 1..65535) return null
        val host = m.groupValues[2].trimEnd('.').lowercase()
        return "${m.groupValues[1].lowercase()}/$host:$port"
    }

    /** Planned or running Zenoh routers used only to seed a missing endpoints.json. Not every tailnet host. */
    val DEFAULT_ROUTERS: List<RouterEntry> = listOf(
        RouterEntry("note4c", "tcp/n910c.tailf52603.ts.net:7447", tailnetName = "n910c.tailf52603.ts.net", note = "Always-on Zenoh router on note4c (SM-N910C). MagicDNS is n910c, not note4."),
        RouterEntry("nitro5", "tcp/nitro5.tailf52603.ts.net:7447", tailnetName = "nitro5.tailf52603.ts.net", note = "Nitro WSL zenohd; sleeps when the lid closes"),
        RouterEntry(
            "box", endpoint = null, tailnetName = "grokbot-user-155710816.tailf52603.ts.net", dialable = false,
            note = "Grok Bot box hub (stores events). Outbound-only: it dials routers listed here or in VAULT_EVENTS_HUB_CONNECT. Not a connect endpoint.",
        ),
    )

    /** `null` if [bytes] is not a JSON object (then the file is left alone). */
    fun parse(bytes: ByteArray?): SharedConfig? {
        val root = bytes?.let { parseObject(it) } ?: return null
        val routers = (root["routers"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            RouterEntry(id, o.str("endpoint"), o.str("tailnetName"), (o["dialable"] as? JsonPrimitive)?.booleanOrNull ?: true, o.str("note"))
        }
        val devices = LinkedHashMap<String, DeviceEntry>()
        (root["devices"] as? JsonObject)?.forEach { (name, el) ->
            val o = el as? JsonObject ?: return@forEach
            val mode = LinkMode.entries.firstOrNull { it.wire == o.str("mode") } ?: return@forEach
            devices[name] = DeviceEntry(
                device = name, app = o.str("app") ?: "", mode = mode,
                listen = (o["listen"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                endpoint = o.str("endpoint"), tailnetName = o.str("tailnetName"), lastSeen = o.str("lastSeen"),
            )
        }
        return SharedConfig(routers, devices)
    }

    /** Where a dial entry came from, for the Settings list. */
    enum class DialSource { SETTINGS, VAULT }

    /** One distinct router in the effective dial list (Settings first, then vault extras). */
    data class DialTarget(val endpoint: String, val source: DialSource, val id: String? = null)

    /**
     * Endpoints to dial: [local] first, then dialable routers, then other devices that accept
     * connections (router/peer with an endpoint). Never this device's own endpoint; invalid entries dropped.
     * Distinct after [normalizeEndpoint]; hostname / MagicDNS / labelled IPv4 aliases of the same
     * device (from [shared] entries) collapse to the first locator.
     */
    fun connectList(local: List<String>, shared: SharedConfig?, self: DeviceEntry): List<String> =
        dialTargets(local, shared, self).map { it.endpoint }

    fun dialTargets(local: List<String>, shared: SharedConfig?, self: DeviceEntry): List<DialTarget> {
        val aliases = HostAliases(shared)
        val out = ArrayList<DialTarget>()
        fun add(raw: String?, source: DialSource, id: String?) {
            val canonical = raw?.let { normalizeEndpoint(it) } ?: return
            if (self.endpoint?.let { aliases.sameRouter(it, canonical) } == true) return
            if (out.any { aliases.sameRouter(it.endpoint, canonical) }) return
            out.add(DialTarget(canonical, source, id))
        }
        local.forEach { add(it, DialSource.SETTINGS, null) }
        if (shared != null) {
            shared.routers.filter { it.dialable && it.id != self.device }.forEach { add(it.endpoint, DialSource.VAULT, it.id) }
            shared.devices.values.filter { it.device != self.device && it.mode != LinkMode.CLIENT }
                .forEach { add(it.endpoint, DialSource.VAULT, it.device) }
        }
        return out
    }

    /** Hosts that should be treated as the same device (MagicDNS, short name, labelled IPv4). */
    internal class HostAliases(shared: SharedConfig?) {
        private val parent = HashMap<String, String>()

        init {
            if (shared != null) {
                for (r in shared.routers) unionAll(hostsOf(r.endpoint, r.tailnetName, r.note))
                for (d in shared.devices.values) unionAll(hostsOf(d.endpoint, d.tailnetName, null))
            }
        }

        fun sameRouter(a: String, b: String): Boolean {
            val ka = key(a) ?: return false
            val kb = key(b) ?: return false
            if (ka == kb) return true
            if (ka.proto != kb.proto || ka.port != kb.port) return false
            return find(ka.host) == find(kb.host)
        }

        private data class Key(val proto: String, val host: String, val port: Int)

        private fun key(e: String): Key? {
            val n = normalizeEndpoint(e) ?: return null
            val slash = n.indexOf('/')
            val colon = n.lastIndexOf(':')
            return Key(n.substring(0, slash), n.substring(slash + 1, colon), n.substring(colon + 1).toInt())
        }

        private fun hostsOf(endpoint: String?, tailnetName: String?, note: String?): Set<String> {
            val out = LinkedHashSet<String>()
            fun addHost(raw: String?) {
                var h = raw?.trim()?.trimEnd('.')?.lowercase() ?: return
                if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
                if (h.isEmpty()) return
                out.add(h)
                if ('.' in h && !h[0].isDigit() && ':' !in h) out.add(h.substringBefore('.'))
            }
            normalizeEndpoint(endpoint ?: "")?.let { n ->
                addHost(n.substringAfter('/').substringBeforeLast(':'))
            }
            addHost(tailnetName)
            if (note != null) ipv4Labeled.findAll(note).forEach { addHost(it.groupValues[1]) }
            return out
        }

        private fun unionAll(hosts: Set<String>) {
            val list = hosts.toList()
            if (list.size < 2) {
                list.forEach { find(it) }
                return
            }
            val root = find(list.first())
            for (h in list.drop(1)) {
                val r = find(h)
                if (r != root) parent[r] = root
            }
        }

        private fun find(h: String): String {
            val cur = parent[h]
            if (cur == null) {
                parent[h] = h
                return h
            }
            if (cur == h) return h
            val r = find(cur)
            parent[h] = r
            return r
        }
    }

    /**
     * New file content with [self] merged in, or `null` when nothing needs writing (same entry and a
     * recent `lastSeen`, or the existing file is not valid JSON and must not be clobbered).
     * A missing file ([existing] `null`) is seeded with [DEFAULT_ROUTERS].
     */
    fun mergeSelf(existing: ByteArray?, self: DeviceEntry, nowMs: Long, refreshMs: Long = LAST_SEEN_REFRESH_MS): ByteArray? {
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val entry = deviceJson(self.copy(lastSeen = nowIso))
        val root: JsonObject = if (existing == null) {
            seed()
        } else {
            val parsed = parseObject(existing) ?: return null
            val old = parsed["devices"] as? JsonObject
            val oldSelf = old?.get(self.device) as? JsonObject
            if (oldSelf != null && sameIgnoringLastSeen(oldSelf, entry) && !stale(oldSelf.str("lastSeen"), nowMs, refreshMs)) return null
            parsed
        }
        val devices = LinkedHashMap((root["devices"] as? JsonObject).orEmpty())
        devices[self.device] = entry
        val next = LinkedHashMap(root)
        if ("schemaVersion" !in next) next["schemaVersion"] = JsonPrimitive(SCHEMA_VERSION)
        next["devices"] = JsonObject(devices)
        return (pretty.encodeToString(JsonElement.serializer(), JsonObject(next)) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun seed(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
            "source" to JsonObject(
                mapOf(
                    "note" to JsonPrimitive(
                        "Vault event sync (Zenoh) endpoints. routers: hosts that can accept connections (dialable false = dial-out only). " +
                            "devices: each device writes only its own entry. No keys or secrets.",
                    ),
                ),
            ),
            "routers" to JsonArray(DEFAULT_ROUTERS.map { routerJson(it) }),
            "devices" to JsonObject(emptyMap()),
        ),
    )

    private fun routerJson(r: RouterEntry): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(r.id))
            r.endpoint?.let { put("endpoint", JsonPrimitive(it)) }
            r.tailnetName?.let { put("tailnetName", JsonPrimitive(it)) }
            put("dialable", JsonPrimitive(r.dialable))
            r.note?.let { put("note", JsonPrimitive(it)) }
        },
    )

    private fun deviceJson(d: DeviceEntry): JsonObject = JsonObject(
        buildMap {
            put("app", JsonPrimitive(d.app))
            put("mode", JsonPrimitive(d.mode.wire))
            put("listen", JsonArray(d.listen.map { JsonPrimitive(it) }))
            d.endpoint?.let { put("endpoint", JsonPrimitive(it)) }
            d.tailnetName?.let { put("tailnetName", JsonPrimitive(it)) }
            d.lastSeen?.let { put("lastSeen", JsonPrimitive(it)) }
        },
    )

    private fun sameIgnoringLastSeen(a: JsonObject, b: JsonObject) = a - "lastSeen" == b - "lastSeen"

    private fun stale(lastSeen: String?, nowMs: Long, refreshMs: Long): Boolean {
        val t = try { lastSeen?.let { Instant.parse(it).toEpochMilli() } } catch (_: DateTimeParseException) { null } ?: return true
        return nowMs - t >= refreshMs || t > nowMs + refreshMs
    }

    private fun parseObject(bytes: ByteArray): JsonObject? =
        runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
