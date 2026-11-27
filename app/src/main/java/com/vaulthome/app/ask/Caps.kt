package com.vaulthome.app.ask

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a provider's capabilities came from. Live Zenoh is authoritative; the rest are fallbacks
 * (see [ProviderMonitor.refresh]).
 */
enum class CapsSource(val label: String) {
    Zenoh("Zenoh"),
    HttpCaps("HTTP /caps"),
    HealthProps("HTTP /health+/props"),
    Registry("devices.json"),
}

/** Persisted last-known caps document for one device. */
data class CapsRecord(
    val device: String,
    val json: String,
    val source: CapsSource,
    val atMs: Long,
)

interface CapsStore {
    fun get(device: String): CapsRecord?
    fun put(record: CapsRecord)
    fun all(): List<CapsRecord>
    fun clear()
}

class MemoryCapsStore : CapsStore {
    private val map = LinkedHashMap<String, CapsRecord>()
    @Synchronized override fun get(device: String) = map[device]
    @Synchronized override fun put(record: CapsRecord) { map[record.device] = record }
    @Synchronized override fun all() = map.values.toList()
    @Synchronized override fun clear() { map.clear() }
}

/** Last-known caps JSON, source and timestamp, in app prefs (survives a process restart). */
class PrefsCapsStore(context: Context) : CapsStore {
    private val prefs = context.getSharedPreferences("vaulthome_caps", Context.MODE_PRIVATE)

    override fun get(device: String): CapsRecord? {
        val json = prefs.getString("json.$device", null) ?: return null
        val source = runCatching { CapsSource.valueOf(prefs.getString("source.$device", "") ?: "") }.getOrNull()
            ?: return null
        val at = prefs.getLong("at.$device", 0L).takeIf { it > 0 } ?: return null
        return CapsRecord(device, json, source, at)
    }

    override fun put(record: CapsRecord) {
        prefs.edit()
            .putString("json.${record.device}", record.json)
            .putString("source.${record.device}", record.source.name)
            .putLong("at.${record.device}", record.atMs)
            .apply()
    }

    override fun all(): List<CapsRecord> = prefs.all.keys.mapNotNull { key ->
        key.removePrefix("json.").takeIf { key.startsWith("json.") && it.isNotBlank() }?.let(::get)
    }

    override fun clear() { prefs.edit().clear().apply() }
}

/** Blocking GET used on the caps worker thread. Null when the request does not complete. */
fun interface CapsHttp {
    fun get(url: String, timeoutMs: Int): Pair<Int, String>?
}

object UrlCapsHttp : CapsHttp {
    override fun get(url: String, timeoutMs: Int): Pair<Int, String>? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            code to body
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

object CapsAge {
    fun label(atMs: Long, nowMs: Long): String {
        val age = (nowMs - atMs).coerceAtLeast(0L)
        return when {
            age < 5_000L -> "just now"
            age < 60_000L -> "${age / 1_000}s ago"
            age < 3_600_000L -> "${age / 60_000}m ago"
            age < 86_400_000L -> "${age / 3_600_000}h ago"
            else -> "${age / 86_400_000}d ago"
        }
    }

    /** e.g. `caps Zenoh · 12s ago` or `caps Zenoh (stale, 2h ago)`. */
    fun line(source: CapsSource, atMs: Long?, stale: Boolean, nowMs: Long): String {
        val age = atMs?.let { label(it, nowMs) }
        return buildString {
            append("caps ${source.label}")
            when {
                stale && age != null -> append(" (stale, $age)")
                stale -> append(" (stale)")
                age != null -> append(" · $age")
            }
        }
    }
}

/** Build a caps v2 document from llama.cpp `/health` + `/props` when no advertised caps document exists. */
object CapsFromProbe {
    fun nCtxFromProps(json: String): Int? {
        val n = JSONObject(json).optJSONObject("default_generation_settings")?.optInt("n_ctx", 0) ?: 0
        return n.takeIf { it > 0 }
    }

    fun healthOk(code: Int, body: String?): Boolean {
        if (code != 200) return false
        if (body.isNullOrBlank()) return true
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return true
        if (root.has("status")) return root.optString("status") == "ok"
        if (root.has("llama")) return root.optString("llama") == "ok"
        return true
    }

    fun syntheticV2(device: String, baseUrl: String, nCtx: Int?, ready: Boolean): String {
        val id = when (device) {
            ProviderRouting.TOWERX_DEVICE -> ProviderRouting.TOWERX_PROVIDER_ID
            ProviderRouting.NITRO_DEVICE -> ProviderRouting.NITRO_PROVIDER_ID
            else -> "$device-qwen"
        }
        val title = when (device) {
            ProviderRouting.TOWERX_DEVICE -> "TowerX chat proxy"
            ProviderRouting.NITRO_DEVICE -> "Nitro chat proxy"
            else -> "$device chat proxy"
        }
        val endpoint = "${AskSettings.normalizeBaseUrl(baseUrl)}/v1/chat/completions"
        val node = JSONObject()
            .put("@id", id)
            .put("@type", "LlmChatProvider")
            .put("label", title)
            .put("comment", "Synthesized from /health and /props (not an advertised caps document)")
            .put("providesSkill", JSONArray().put(Skills.VAULT))
            .put("hostedOn", JSONObject().put("deviceId", device))
            .put("endpointUrl", endpoint)
            .put("transport", "ChatProxy")
            .put("scope", "Tailnet")
            .put("accessMode", "ReadOnly")
            .put("ready", ready)
        if (nCtx != null) node.put("nCtx", nCtx)
        return JSONObject().put("schemaVersion", 2).put("@graph", JSONArray().put(node)).toString()
    }
}

fun List<Provider>.stamped(source: CapsSource, atMs: Long, stale: Boolean): List<Provider> =
    map { it.copy(capsSource = source, capsAtMs = atMs, capsStale = stale) }
