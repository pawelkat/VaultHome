package com.vaulthome.app.devices

import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote power descriptor on a device (e.g. TowerX): which Zenoh controller key to call and the rules around it.
 * The page renders buttons from this, nothing here is TowerX-specific.
 *
 * @property controller id of the registry device that is the power board (`towerx-power`).
 * @property key Zenoh key prefix of that board; an action is a get on `<key>/call/<tool>`.
 * @property actions subset of `on`, `off`, `status`.
 * @property cooldownS the board ignores another pulse for this long after one.
 * @property confirm actions that need a confirmation dialog.
 * @property forceOffHoldS how long `off` holds the power button (shown in the confirm dialog).
 */
data class PowerDescriptor(
    val controller: String,
    val key: String,
    val actions: List<String>,
    val cooldownS: Int,
    val confirm: List<String>,
    val forceOffHoldS: Int,
)

/**
 * One entry of `app_settings/VaultEvents/devices.json` (schema v1).
 *
 * @property presence Zenoh presence id (`vault/presence/<presence>`); defaults to [id].
 * @property liveliness extra liveliness token for devices that only have a token (the ESP32 board).
 * @property chatProxy chat proxy base URL (health is `<chatProxy>/health`), null if the device has none.
 */
data class RegDevice(
    val id: String,
    val name: String,
    val kind: String,
    val endpoints: Map<String, String> = emptyMap(),
    val chatProxy: String? = null,
    val presence: String? = null,
    val liveliness: String? = null,
    val via: String? = null,
    val power: PowerDescriptor? = null,
    val note: String? = null,
) {
    val presenceId: String get() = presence ?: id
    val isPowerBoard: Boolean get() = kind == KIND_POWER_BOARD

    companion object {
        const val KIND_PC = "pc"
        const val KIND_PHONE = "phone"
        const val KIND_POWER_BOARD = "power-board"
    }
}

data class DeviceRegistry(val version: Int, val devices: List<RegDevice>) {
    fun byId(id: String): RegDevice? = devices.firstOrNull { it.id == id }
}

/** Which copy of the registry is in use (shown on the Devices page). */
enum class RegistrySource(val label: String) {
    Vault("vault file"),
    Cache("last good local copy"),
    Bundled("built-in defaults"),
}

data class LoadedRegistry(val registry: DeviceRegistry, val source: RegistrySource, val problem: String? = null)

/** Parsing and encoding of `devices.json`. Unknown fields are ignored, a missing or newer `version` is an error. */
object DeviceRegistryJson {
    const val VAULT_PATH = "app_settings/VaultEvents/devices.json"
    const val VERSION = 1

    fun parse(json: String): Result<DeviceRegistry> = runCatching {
        val root = JSONObject(json)
        require(root.optInt("version", -1) == VERSION) { "unsupported devices.json version ${root.opt("version")}" }
        val arr = root.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::device) }
        require(devices.map { it.id }.toSet().size == devices.size) { "duplicate device id" }
        DeviceRegistry(VERSION, devices)
    }

    private fun device(o: JSONObject): RegDevice? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val ep = o.optJSONObject("endpoints")
        val endpoints = if (ep == null) emptyMap() else ep.keys().asSequence()
            .mapNotNull { k -> ep.optString(k).takeIf { v -> v.isNotBlank() }?.let { v -> k to v } }.toMap()
        return RegDevice(
            id = id,
            name = o.optString("name").ifBlank { id },
            kind = o.optString("kind").ifBlank { "unknown" },
            endpoints = endpoints,
            chatProxy = text(o, "chat_proxy")?.trimEnd('/'),
            presence = text(o, "presence"),
            liveliness = text(o, "liveliness"),
            via = text(o, "via"),
            power = o.optJSONObject("power")?.let(::power),
            note = text(o, "note"),
        )
    }

    private fun power(o: JSONObject): PowerDescriptor? {
        val controller = text(o, "controller") ?: return null
        val key = text(o, "key") ?: return null
        val actions = strings(o.optJSONArray("actions")).filter { it in ACTIONS }
        return PowerDescriptor(
            controller = controller,
            key = key,
            actions = actions,
            cooldownS = o.optInt("cooldown_s", 0).coerceAtLeast(0),
            confirm = strings(o.optJSONArray("confirm")),
            forceOffHoldS = o.optInt("force_off_hold_s", 0).coerceAtLeast(0),
        )
    }

    val ACTIONS = listOf("on", "off", "status")

    private fun text(o: JSONObject, key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    fun encode(r: DeviceRegistry): String {
        val devices = JSONArray()
        r.devices.forEach { d ->
            val o = JSONObject().put("id", d.id).put("name", d.name).put("kind", d.kind)
            if (d.endpoints.isNotEmpty()) o.put("endpoints", JSONObject(d.endpoints))
            d.chatProxy?.let { o.put("chat_proxy", it) }
            d.presence?.let { o.put("presence", it) }
            d.liveliness?.let { o.put("liveliness", it) }
            d.via?.let { o.put("via", it) }
            d.note?.let { o.put("note", it) }
            d.power?.let { p ->
                o.put(
                    "power",
                    JSONObject().put("controller", p.controller).put("key", p.key)
                        .put("actions", JSONArray(p.actions)).put("cooldown_s", p.cooldownS)
                        .put("confirm", JSONArray(p.confirm)).put("force_off_hold_s", p.forceOffHoldS),
                )
            }
            devices.put(o)
        }
        return JSONObject().put("version", r.version).put("devices", devices).toString(2) + "\n"
    }

    /**
     * Built-in device list, used to seed the vault file (only when it is missing) and as the last-resort fallback.
     * No chat-proxy URLs are baked in: [com.vaulthome.app.ask.DeviceUrls] derives `https://<id>.<tailnet-suffix>`.
     * A later devices.json may add `endpoints` or `chat_proxy` to override, including a `lan` URL to try.
     */
    fun bundled(): DeviceRegistry = DeviceRegistry(
        VERSION,
        listOf(
            RegDevice(
                id = "nitro5", name = "Nitro 5", kind = RegDevice.KIND_PC,
                note = "Chat proxy via Tailscale Serve; sleeps when the lid closes.",
            ),
            RegDevice(
                id = "towerx", name = "TowerX", kind = RegDevice.KIND_PC,
                power = PowerDescriptor(
                    controller = "towerx-power", key = "devices/towerx-power",
                    actions = listOf("on", "off", "status"), cooldownS = 45, confirm = listOf("off"), forceOffHoldS = 6,
                ),
            ),
            RegDevice(
                id = "towerx-power", name = "TowerX power board", kind = RegDevice.KIND_POWER_BOARD,
                liveliness = "devices/towerx-power", via = "note4c",
                note = "ESP32; only a Zenoh liveliness token. Calls go through the zenoh-mcp-device-bridge on note4c.",
            ),
        ),
    )
}
