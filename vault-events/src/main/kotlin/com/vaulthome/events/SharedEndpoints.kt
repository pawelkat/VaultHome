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
    private val endpointRe = Regex("(tcp|udp|tls|quic|ws)/(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):([0-9]{1,5})")

    fun isEndpoint(e: String): Boolean = endpointRe.matchEntire(e)?.let { it.groupValues[3].toInt() in 1..65535 } == true

    /** Known hosts on Pawel's tailnet (names from vault notes / tailnet DNS), used to seed the file. */
    val DEFAULT_ROUTERS: List<RouterEntry> = listOf(
        RouterEntry("note4", "tcp/note4.tailf52603.ts.net:7447", note = "planned always-on router (Termux, on charger); not on the tailnet on 2026-11-18"),
        RouterEntry("nitro5", "tcp/nitro5.tailf52603.ts.net:7447", tailnetName = "nitro5.tailf52603.ts.net", note = "Nitro WSL; no router running yet; sleeps when the lid closes"),
        RouterEntry("latitude", "tcp/pawelk-latitude-5300.tailf52603.ts.net:7447", tailnetName = "pawelk-latitude-5300.tailf52603.ts.net", note = "Latitude 5300; no router running yet"),
        RouterEntry("desktop-sdf3lgo", "tcp/desktop-sdf3lgo.tailf52603.ts.net:7447", tailnetName = "desktop-sdf3lgo.tailf52603.ts.net", note = "WSL host; no router running yet"),
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

    /**
     * Endpoints to dial: [local] first, then dialable routers, then other devices that accept
     * connections (router/peer with an endpoint). Never this device's own endpoint; invalid entries dropped.
     */
    fun connectList(local: List<String>, shared: SharedConfig?, self: DeviceEntry): List<String> {
        val out = LinkedHashSet<String>()
        local.filterTo(out) { isEndpoint(it) }
        if (shared != null) {
            shared.routers.filter { it.dialable && it.id != self.device }.mapNotNullTo(out) { r -> r.endpoint?.takeIf { isEndpoint(it) } }
            shared.devices.values.filter { it.device != self.device && it.mode != LinkMode.CLIENT }
                .mapNotNullTo(out) { d -> d.endpoint?.takeIf { isEndpoint(it) } }
        }
        self.endpoint?.let { out.remove(it) }
        return out.toList()
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
