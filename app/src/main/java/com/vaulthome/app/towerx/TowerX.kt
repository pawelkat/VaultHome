package com.vaulthome.app.towerx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TowerX power and Ask availability over Zenoh (vault notes `Grok Bot - TowerX power`, `Zenoh MCP Device Bridge`).
 * The ESP32 only declares the liveliness token [POWER_TOKEN]; `zenoh-mcp-device-bridge` on note4c answers the
 * `call/<tool>` queries. VaultHome never talks to the ESP32 or SSHes anywhere.
 */
object TowerXKeys {
    const val POWER_TOKEN = "devices/towerx-power"
    /** vault-events agent on TowerX. */
    const val AGENT_TOKEN = "vault/presence/towerx"
    const val OFF_PAYLOAD = """{"confirm":true}"""
    fun call(tool: String, key: String = POWER_TOKEN) = "$key/call/$tool"
    val WATCH = listOf(POWER_TOKEN, AGENT_TOKEN)
}

enum class PowerAction(val tool: String, val label: String) {
    Status("status", "Status"), On("power_on", "Turn on"), Off("power_off", "Force off")
}

/**
 * Live view. `null` = unknown (no Zenoh session, i.e. event sync is off).
 * [healthOk] is the last TowerX chat proxy `/health` (200 only when llama-server and vault-searchd are both up).
 */
data class TowerXState(
    val espAlive: Boolean? = null,
    val agentAlive: Boolean? = null,
    val healthOk: Boolean? = null,
    val healthDetail: String = "",
    val lastReply: String = "",
    val busy: Boolean = false,
) {
    /** Ask may use TowerX: its agent is not known offline and /health was OK. */
    val askAvailable: Boolean get() = TowerXRules.askAvailable(agentAlive, healthOk)
}

object TowerXRules {
    /**
     * TowerX is used for Ask when its Zenoh agent token is alive and its /health is OK. Without a Zenoh session
     * ([agentAlive] `null`) the health check alone decides, so Ask still works with event sync off.
     */
    fun askAvailable(agentAlive: Boolean?, healthOk: Boolean?): Boolean = healthOk == true && agentAlive != false

    /** Bridge reply `{"ok":true,"result":{"text":".."}}` or `{"ok":false,"error":".."}`. */
    fun parseReply(text: String?): Result<String> {
        if (text == null) return Result.failure(IllegalStateException("no reply from the note4c bridge"))
        val o = runCatching { JSONObject(text) }.getOrElse { return Result.failure(IllegalStateException("bad reply: ${text.take(120)}")) }
        return if (o.optBoolean("ok")) {
            val r = o.opt("result")
            Result.success((r as? JSONObject)?.optString("text")?.takeIf { it.isNotEmpty() } ?: r?.toString() ?: "ok")
        } else Result.failure(IllegalStateException(o.optString("error").ifBlank { "bridge said ok=false" }))
    }

    /** `null` = may send; else why not. */
    fun refuse(action: PowerAction, espAlive: Boolean?, connected: Boolean, confirmed: Boolean): String? = when {
        !connected -> "Event sync is off: no Zenoh session"
        espAlive != true -> "ESP32 offline (no ${TowerXKeys.POWER_TOKEN} token): nothing sent"
        action == PowerAction.Off && !confirmed -> "Force off needs confirmation"
        else -> null
    }
}

object TowerXMonitor {
    private val mutable = MutableStateFlow(TowerXState())
    val state: StateFlow<TowerXState> = mutable

    /** Set by the event sync service while its Zenoh session runs: `(selector, payload, timeoutMs) -> reply`. */
    @Volatile var caller: ((String, String?, Long) -> String?)? = null

    fun onToken(key: String, alive: Boolean) = mutable.update {
        when (key) {
            TowerXKeys.POWER_TOKEN -> it.copy(espAlive = alive)
            TowerXKeys.AGENT_TOKEN -> it.copy(agentAlive = alive)
            else -> it
        }
    }

    /** Session closed: tokens are unknown again. */
    fun onSessionClosed() = mutable.update { it.copy(espAlive = null, agentAlive = null) }

    fun onHealth(ok: Boolean, detail: String) = mutable.update { it.copy(healthOk = ok, healthDetail = detail) }

    /**
     * [key] is the board's Zenoh key prefix from the registry's power descriptor. Only [TowerXKeys.POWER_TOKEN] has a
     * liveliness source today; any other key is refused until the monitor learns to watch it.
     */
    suspend fun power(action: PowerAction, confirmed: Boolean = false, key: String = TowerXKeys.POWER_TOKEN): Result<String> {
        val c = caller
        TowerXRules.refuse(action, if (key == TowerXKeys.POWER_TOKEN) mutable.value.espAlive else null, c != null, confirmed)?.let {
            mutable.update { s -> s.copy(lastReply = it) }
            return Result.failure(IllegalStateException(it))
        }
        mutable.update { it.copy(busy = true) }
        val r = withContext(Dispatchers.IO) {
            TowerXRules.parseReply(
                runCatching { c!!(TowerXKeys.call(action.tool, key), if (action == PowerAction.Off) TowerXKeys.OFF_PAYLOAD else null, 15_000) }.getOrNull(),
            )
        }
        mutable.update { it.copy(busy = false, lastReply = r.fold({ v -> "${action.label}: $v" }, { e -> "${action.label} failed: ${e.message}" })) }
        return r
    }
}
