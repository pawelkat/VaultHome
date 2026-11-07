package com.vaulthome.app.x4

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class X4Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Parsed `panel_status` text (`key: value` lines). */
data class X4Status(val raw: String) {
    private val fields: Map<String, String> = raw.lines().mapNotNull { line ->
        val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()
    val url: String? get() = fields["url"]
    val refreshing: Boolean? get() = fields["refreshing"]?.toBooleanStrictOrNull()
    val stored: String? get() = fields["stored"]
    val shown: String? get() = fields["shown"]
    val pages: Int? get() = fields["pages"]?.toIntOrNull()
}

/** Result of showing a note: the device's own sentence plus the page count parsed from it. */
data class X4ShowResult(val message: String, val pages: Int?, val cut: Boolean, val status: X4Status?)

/** A device seen advertising (BLE scan). */
data class X4Found(val name: String, val address: String) {
    val label: String get() = if (name.isNotBlank() && address.isNotBlank()) "$name · $address" else name.ifBlank { address }
}

/** What the app needs from the X4; an interface so the ViewModel can be faked. */
interface X4Gateway {
    /** Short probe (BLE scan). Throws [X4Exception] when the reader is not advertising. */
    suspend fun probe(settings: X4Settings): X4Found
    suspend fun showText(settings: X4Settings, markdown: String): X4ShowResult
}

/**
 * Minimal MCP (streamable HTTP, JSON replies) client for the X4 firmware (`VaultHomeDisp` branch `feat/x4`).
 *
 * The firmware serves **one TCP connection at a time** and refuses a second while the first is open, so a whole
 * session (initialize → notifications/initialized → tools/call → panel_status) runs over a single raw socket with
 * HTTP/1.1 keep-alive. Calls are serialised with a mutex so a probe never overlaps a send. Plain `Socket` (not
 * `HttpURLConnection`) because the platform client may silently open a second connection.
 */
class X4McpClient(
    private val probeTimeoutMs: Int = 2_000,
    private val connectTimeoutMs: Int = 4_000,
    /** show_text lays out up to 24 pages before it replies. */
    private val sendReadTimeoutMs: Int = 30_000,
) {
    private val lock = Mutex()

    suspend fun probe(baseUrl: String): X4Status = session(baseUrl, probeTimeoutMs, probeTimeoutMs) { s ->
        s.initialize()
        X4Status(s.callTool("panel_status", JSONObject()).text)
    }

    suspend fun showText(baseUrl: String, markdown: String): X4ShowResult =
        session(baseUrl, connectTimeoutMs, sendReadTimeoutMs) { s ->
            s.initialize()
            s.notifyInitialized()
            val reply = s.callTool("show_text", JSONObject().put("text", markdown))
            if (reply.isError) throw X4Exception("X4: ${reply.text.ifBlank { "show_text failed" }}")
            val status = runCatching { X4Status(s.callTool("panel_status", JSONObject()).text) }.getOrNull()
            McpJson.showResult(reply.text, status)
        }

    private suspend fun <T> session(baseUrl: String, connectMs: Int, readMs: Int, block: (Session) -> T): T =
        lock.withLock {
            withContext(Dispatchers.IO) {
                X4Settings.problem(baseUrl)?.let { throw X4Exception(it) }
                val host = X4Settings.host(baseUrl)
                val port = X4Settings.port(baseUrl)
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), connectMs)
                    socket.soTimeout = readMs
                    block(Session(socket, host, port))
                } catch (e: X4Exception) {
                    throw e
                } catch (e: Exception) {
                    throw X4Exception(describe(e, host), e)
                } finally {
                    runCatching { socket.close() }
                }
            }
        }

    private fun describe(e: Exception, host: String): String = when (e) {
        is SocketTimeoutException -> "X4 at $host did not answer (timed out)"
        is ConnectException -> "X4 at $host refused the connection (off, other client, or wrong address)"
        is UnknownHostException -> "X4 host $host not found"
        else -> "X4 at $host: " + (e.message ?: e.javaClass.simpleName)
    }

    /** One MCP session on one TCP connection. */
    private class Session(private val socket: Socket, private val host: String, private val port: Int) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private var sessionId: String? = null
        private var nextId = 1

        fun initialize() {
            val reply = post(McpJson.initialize(nextId++))
            if (reply.code != 200) throw X4Exception("X4 initialize failed: HTTP ${reply.code}")
            sessionId = reply.header("mcp-session-id") ?: throw X4Exception("X4 sent no Mcp-Session-Id")
            McpJson.result(reply.body) // throws on JSON-RPC error
        }

        fun notifyInitialized() {
            val reply = post(McpJson.initializedNotification())
            if (reply.code !in 200..299) throw X4Exception("X4 notifications/initialized failed: HTTP ${reply.code}")
        }

        fun callTool(name: String, arguments: JSONObject): McpJson.ToolReply {
            val reply = post(McpJson.toolsCall(nextId++, name, arguments))
            if (reply.code == 404) throw X4Exception("X4 dropped the MCP session (another client initialized?)")
            if (reply.code != 200) throw X4Exception("X4 $name failed: HTTP ${reply.code} ${reply.body.take(120)}".trim())
            return McpJson.toolReply(reply.body)
        }

        private fun post(json: String): HttpReply {
            val body = json.toByteArray(Charsets.UTF_8)
            if (body.size > X4Payload.MAX_REQUEST_BYTES) throw X4Exception("Request is too large for the X4 (${body.size} bytes)")
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\n")
                append("Host: ").append(if (port == 80) host else "$host:$port").append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Accept: application/json\r\n")
                append("User-Agent: VaultHome/0.1\r\n")
                append("Connection: keep-alive\r\n")
                sessionId?.let {
                    append("Mcp-Session-Id: ").append(it).append("\r\n")
                    append("MCP-Protocol-Version: ").append(McpJson.PROTOCOL).append("\r\n")
                }
                append("Content-Length: ").append(body.size).append("\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
            return HttpReply.read(input)
        }
    }
}

/** Just enough HTTP/1.1 response parsing for the firmware (Content-Length) and test servers (chunked too). */
internal class HttpReply(val code: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        fun read(input: InputStream): HttpReply {
            val status = readLine(input) ?: throw X4Exception("X4 closed the connection")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw X4Exception("Bad HTTP status line from X4")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: throw X4Exception("X4 closed the connection mid-headers")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val bytes = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                headers["content-length"] != null -> readExactly(input, headers["content-length"]!!.toInt())
                code == 204 || code == 304 || code in 100..199 -> ByteArray(0)
                else -> input.readBytes() // no length: body runs to close
            }
            return HttpReply(code, headers, String(bytes, Charsets.UTF_8))
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(Charsets.US_ASCII.name())
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) throw X4Exception("X4 reply was cut short")
                off += r
            }
            return buf
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = (readLine(input) ?: throw X4Exception("X4 reply was cut short")).substringBefore(';').trim().toInt(16)
                if (size == 0) { while (!readLine(input).isNullOrEmpty()) { }; break }
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }
    }
}

/** JSON-RPC bodies exactly as in the vault note "Grok Bot - X4 markdown". */
object McpJson {
    const val PROTOCOL = "2025-03-26"

    fun initialize(id: Int): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("method", "initialize")
        .put("params", JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "vault-home").put("version", "0.1.0")))
        .toString()

    fun initializedNotification(): String =
        JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()

    fun toolsCall(id: Int, name: String, arguments: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
        .put("params", JSONObject().put("name", name).put("arguments", arguments))
        .toString()

    data class ToolReply(val text: String, val isError: Boolean)

    /** `result` of a JSON-RPC reply; a JSON-RPC `error` becomes [X4Exception]. Tolerates an SSE `data:` frame. */
    fun result(body: String): JSONObject {
        val json = body.trim().let { b ->
            if (b.startsWith("{")) b else b.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: b
        }
        val root = try { JSONObject(json) } catch (e: Exception) { throw X4Exception("X4 sent invalid JSON", e) }
        root.optJSONObject("error")?.let { throw X4Exception("X4 error: ${it.optString("message", "unknown")}") }
        return root.optJSONObject("result") ?: throw X4Exception("X4 reply has no result")
    }

    fun toolReply(body: String): ToolReply {
        val result = result(body)
        val content = result.optJSONArray("content")
        val text = buildString {
            if (content != null) for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") { if (isNotEmpty()) append('\n'); append(part.optString("text")) }
            }
        }
        return ToolReply(text, result.optBoolean("isError", false))
    }

    private val PAGES = Regex("""(\d+)\s+page""")

    fun showResult(text: String, status: X4Status?): X4ShowResult = X4ShowResult(
        message = text,
        pages = PAGES.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        cut = text.contains("cut", ignoreCase = true),
        status = status,
    )
}
