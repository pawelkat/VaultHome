package com.vaulthome.app.x4.mcp

import com.vaulthome.app.x4.X4Exception
import com.vaulthome.app.x4.X4Gateway
import com.vaulthome.app.x4.X4Payload
import com.vaulthome.app.x4.X4Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One HTTP request as the MCP endpoint sees it (header names lowercased). */
data class McpHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class McpHttpResponse(
    val code: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "application/json",
)

/**
 * Display MCP that VaultHome hosts for Grok Bot (streamable HTTP, JSON replies, no SSE).
 *
 * Same tool names/shape as the X4 firmware's old Wi-Fi MCP (vault note "Grok Bot - X4 markdown"), but the
 * transport to the reader is **BLE** via [X4Gateway] (vault note "Grok Bot - X4 BLE"): the flashed image
 * (`baac775`) has no Wi-Fi. `show_text` succeeds only when the reader notifies `pages:N`.
 * MCP security is deferred (no auth), as for the other panel MCPs.
 */
class X4McpHandler(
    private val gateway: X4Gateway,
    private val settings: () -> X4Settings,
    private val newSessionId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    /** Serialises BLE work so overlapping MCP calls never interleave GATT sessions. */
    private val bleLock = Mutex()
    /** Session id → negotiated protocol version. */
    private val sessions = ConcurrentHashMap<String, String>()
    @Volatile var lastResult: String? = null
        private set

    fun handle(req: McpHttpRequest): McpHttpResponse = runBlocking { handleSuspend(req) }

    suspend fun handleSuspend(req: McpHttpRequest): McpHttpResponse {
        val path = req.path.substringBefore('?').trimEnd('/')
        if (path != "/mcp") return plain(404, "Not found. The MCP endpoint is POST /mcp")
        return when (req.method.uppercase()) {
            "POST" -> post(req)
            "DELETE" -> {
                val id = req.header(HEADER_SESSION)
                if (id != null && sessions.remove(id) != null) McpHttpResponse(200, "") else plain(404, "Unknown MCP session")
            }
            "GET" -> plain(405, "This server sends JSON replies only (no SSE stream). Use POST /mcp", mapOf("Allow" to "POST, DELETE"))
            else -> plain(405, "Use POST /mcp", mapOf("Allow" to "POST, DELETE"))
        }
    }

    private suspend fun post(req: McpHttpRequest): McpHttpResponse {
        val parsed: Any = try {
            val b = req.body.trim()
            if (b.startsWith("[")) JSONArray(b) else JSONObject(b)
        } catch (e: Exception) {
            return json(400, error(JSONObject.NULL, PARSE_ERROR, "Parse error: body is not JSON"))
        }
        val messages: List<JSONObject> = when (parsed) {
            is JSONArray -> (0 until parsed.length()).map {
                parsed.optJSONObject(it) ?: return json(400, error(JSONObject.NULL, INVALID_REQUEST, "Batch entries must be objects"))
            }
            else -> listOf(parsed as JSONObject)
        }
        if (messages.isEmpty()) return json(400, error(JSONObject.NULL, INVALID_REQUEST, "Empty batch"))

        val isInit = messages.any { it.optString("method") == "initialize" }
        if (isInit) {
            if (messages.size != 1) return json(400, error(JSONObject.NULL, INVALID_REQUEST, "initialize must not be batched"))
            return initialize(messages[0])
        }

        val sessionId = req.header(HEADER_SESSION)
            ?: return json(400, error(idOf(messages[0]), INVALID_REQUEST, "Missing Mcp-Session-Id header; call initialize first"))
        val version = sessions[sessionId]
            ?: return json(404, error(idOf(messages[0]), INVALID_REQUEST, "Unknown or expired Mcp-Session-Id; initialize again"))
        req.header(HEADER_PROTOCOL)?.let { v ->
            if (v !in SUPPORTED_VERSIONS) {
                return json(400, error(idOf(messages[0]), INVALID_REQUEST, "Unsupported MCP-Protocol-Version $v"), sessionHeaders(sessionId, version))
            }
        }
        val headers = sessionHeaders(sessionId, version)

        val replies = JSONArray()
        for (m in messages) {
            dispatch(m)?.let { replies.put(it) }
        }
        if (replies.length() == 0) return McpHttpResponse(202, "", headers)
        val body = if (parsed is JSONArray) replies.toString() else replies.getJSONObject(0).toString()
        return McpHttpResponse(200, body, headers)
    }

    private fun initialize(msg: JSONObject): McpHttpResponse {
        val requested = msg.optJSONObject("params")?.optString("protocolVersion").orEmpty()
        val version = if (requested in SUPPORTED_VERSIONS) requested else PROTOCOL
        val id = newSessionId()
        sessions[id] = version
        val result = JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
            .put("instructions", INSTRUCTIONS)
        return McpHttpResponse(200, ok(idOf(msg), result).toString(), sessionHeaders(id, version))
    }

    /** One JSON-RPC message → reply, or null for notifications/responses. */
    private suspend fun dispatch(msg: JSONObject): JSONObject? {
        val method = msg.optString("method", "")
        val hasId = msg.has("id")
        if (method.isEmpty()) return if (hasId && !msg.has("result") && !msg.has("error")) error(idOf(msg), INVALID_REQUEST, "No method") else null
        if (!hasId) return null // notifications/initialized, notifications/cancelled, …
        val id = idOf(msg)
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "ping" -> ok(id, JSONObject())
            "tools/list" -> ok(id, JSONObject().put("tools", tools()))
            "tools/call" -> {
                val name = params.optString("name", "")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                when (name) {
                    "show_text" -> ok(id, showText(args))
                    "panel_status" -> ok(id, panelStatus())
                    "show_bitmap" -> ok(id, toolText(BITMAP_REFUSED, isError = true))
                    else -> error(id, INVALID_PARAMS, "Unknown tool: $name")
                }
            }
            "resources/list" -> ok(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> ok(id, JSONObject().put("prompts", JSONArray()))
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private suspend fun showText(args: JSONObject): JSONObject {
        val text = when {
            args.has("text") && args.get("text") is String -> args.getString("text")
            args.has("markdown") && args.get("markdown") is String -> args.getString("markdown")
            else -> return toolText("show_text needs a string argument \"text\" (markdown, sent unchanged)", isError = true)
        }
        val path = args.optString("path", "")
        val check = if (path.isNotBlank()) {
            when (val byPath = X4Payload.forNote(path, text)) {
                is X4Payload.Check.Refused -> byPath
                is X4Payload.Check.Ok -> X4Payload.forAnswer(text)
            }
        } else X4Payload.forAnswer(text)
        val markdown = when (check) {
            is X4Payload.Check.Refused -> return toolText("Not sent: ${check.reason}", isError = true)
            is X4Payload.Check.Ok -> check.text
        }
        val s = settings()
        return try {
            val r = bleLock.withLock { gateway.showText(s, markdown) }
            val pages = r.pages ?: return toolText("Not confirmed: the X4 sent no pages:N (got \"${r.message}\")", isError = true)
            val cut = if (r.cut) " — cut at the 24-page limit" else ""
            val msg = "Shown on the X4 over BLE: $pages page(s)$cut (pages:$pages). The glass refreshes in a few seconds."
            lastResult = "pages:$pages"
            toolText(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolText("Not sent: " + unreachable(s, e), isError = true)
        }
    }

    private suspend fun panelStatus(): JSONObject {
        val s = settings()
        return try {
            val found = bleLock.withLock { gateway.probe(s) }
            toolText(
                buildString {
                    append("transport: ble\n")
                    append("reachable: true\n")
                    append("name: ").append(found.name.ifBlank { s.deviceName }).append('\n')
                    append("address: ").append(found.address.ifBlank { s.addressHint }).append('\n')
                    append("orientation: portrait 480x800 (device lays out markdown)\n")
                    append("text_max: ").append(X4Payload.MAX_CHARS).append(" characters\n")
                    append("bitmap: not supported over BLE\n")
                    append("last: ").append(lastResult ?: "none this session")
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolText(
                "transport: ble\nreachable: false\nname: ${s.deviceName}\naddress hint: ${s.addressHint}\nreason: " + unreachable(s, e),
                isError = true,
            )
        }
    }

    private fun unreachable(s: X4Settings, e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (e is X4Exception && m.contains("not advertising", ignoreCase = true)) {
            "X4 unreachable: ${s.deviceName} is not advertising over BLE — the reader is asleep (small open power mark) " +
                "or out of range of the phone. Press any key on the X4 to wake it (do not hold power), then try again."
        } else "X4 unreachable over BLE: $m"
    }

    private fun sessionHeaders(id: String, version: String) =
        mapOf("Mcp-Session-Id" to id, "MCP-Protocol-Version" to version)

    companion object {
        const val PROTOCOL = "2025-03-26"
        val SUPPORTED_VERSIONS = setOf("2025-03-26", "2024-11-05", "2025-06-18")
        const val SERVER_NAME = "vaulthome-x4"
        const val SERVER_VERSION = "0.1.0"
        const val HEADER_SESSION = "mcp-session-id"
        const val HEADER_PROTOCOL = "mcp-protocol-version"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        const val BITMAP_REFUSED = "show_bitmap is not available: VaultHome reaches the X4 over BLE, which carries " +
            "markdown text only (no bitmap protocol in the flashed image). Send the markdown with show_text; the device lays out the pages."

        const val INSTRUCTIONS = "VaultHome forwards show_text to the XTEINK X4 e-ink reader over Bluetooth LE. " +
            "Pass the markdown unchanged (max 32000 characters); the device lays out the pages. Never send VaultKeep notes. " +
            "If the X4 is unreachable, ask Pawel to press any key on the reader to wake it."

        fun tools(): JSONArray = JSONArray()
            .put(
                JSONObject().put("name", "show_text")
                    .put("description", "Show markdown on the X4 e-ink reader (480x800 portrait). The device lays out up to 24 pages. " +
                        "Text is sent unchanged over BLE; max 32000 characters; VaultKeep is refused. Succeeds only when the reader reports pages:N.")
                    .put("inputSchema", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("text", JSONObject().put("type", "string").put("description", "Markdown to show, unchanged"))
                            .put("path", JSONObject().put("type", "string").put("description", "Optional vault path of the note (VaultKeep is refused)")))
                        .put("required", JSONArray().put("text"))),
            )
            .put(
                JSONObject().put("name", "panel_status")
                    .put("description", "Scan for the X4 over BLE and report whether it is reachable (name, address, last result).")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())),
            )
            .put(
                JSONObject().put("name", "show_bitmap")
                    .put("description", "Not supported over BLE; always returns an error. Use show_text.")
                    .put("inputSchema", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("bitmap", JSONObject().put("type", "string")))),
            )

        private fun idOf(msg: JSONObject): Any = if (msg.has("id")) msg.get("id") else JSONObject.NULL

        fun ok(id: Any, result: JSONObject): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

        fun error(id: Any, code: Int, message: String): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message))

        fun toolText(text: String, isError: Boolean = false): JSONObject = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError)

        private fun json(code: Int, body: JSONObject, headers: Map<String, String> = emptyMap()) =
            McpHttpResponse(code, body.toString(), headers)

        private fun plain(code: Int, text: String, headers: Map<String, String> = emptyMap()) =
            McpHttpResponse(code, text, headers, contentType = "text/plain; charset=utf-8")
    }
}
