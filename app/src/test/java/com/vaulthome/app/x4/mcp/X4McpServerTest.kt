package com.vaulthome.app.x4.mcp

import com.vaulthome.app.x4.X4Exception
import com.vaulthome.app.x4.X4Found
import com.vaulthome.app.x4.X4Gateway
import com.vaulthome.app.x4.X4McpClient
import com.vaulthome.app.x4.X4Settings
import com.vaulthome.app.x4.X4ShowResult
import com.vaulthome.app.x4.X4Status
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private class FakeGateway(
    var found: X4Found? = X4Found("VaultHome X4", "C2:45:6B:2D:4E:FC"),
    var reply: (String) -> X4ShowResult = { X4ShowResult("pages:3", 3, false, X4Status("pages:3")) },
    val delayMs: Long = 0,
) : X4Gateway {
    val sent = mutableListOf<String>()
    val active = AtomicInteger(0)
    var maxActive = 0

    private suspend fun <T> busy(block: () -> T): T {
        val n = active.incrementAndGet()
        synchronized(this) { maxActive = maxOf(maxActive, n) }
        try { if (delayMs > 0) delay(delayMs); return block() } finally { active.decrementAndGet() }
    }

    override suspend fun probe(settings: X4Settings): X4Found = busy {
        found ?: throw X4Exception("not advertising — press any key to wake it, then try again")
    }

    override suspend fun showText(settings: X4Settings, markdown: String): X4ShowResult = busy {
        if (found == null) throw X4Exception("not advertising — press any key to wake it, then try again")
        synchronized(sent) { sent.add(markdown) }
        reply(markdown)
    }
}

class X4McpServerTest {
    private val gw = FakeGateway()
    private val handler = X4McpHandler(gw, { X4Settings() }, newSessionId = { "sess-1" })

    private fun post(body: String, session: String? = "sess-1", version: String? = "2025-03-26") =
        handler.handle(
            McpHttpRequest(
                "POST", "/mcp",
                buildMap {
                    put("content-type", "application/json")
                    session?.let { put("mcp-session-id", it) }
                    version?.let { put("mcp-protocol-version", it) }
                },
                body,
            ),
        )

    private fun init(version: String = "2025-03-26") = post(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"grok","version":"1"}}}""",
        session = null, version = null,
    )

    private fun call(name: String, args: JSONObject = JSONObject(), id: Int = 2): JSONObject {
        val r = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args)).toString())
        assertEquals(200, r.code)
        return JSONObject(r.body).getJSONObject("result")
    }

    private fun text(result: JSONObject) = result.getJSONArray("content").getJSONObject(0).getString("text")

    @Test fun initializeReturnsSessionAndProtocol() {
        val r = init()
        assertEquals(200, r.code)
        assertEquals("sess-1", r.headers["Mcp-Session-Id"])
        assertEquals("2025-03-26", r.headers["MCP-Protocol-Version"])
        val result = JSONObject(r.body).getJSONObject("result")
        assertEquals("2025-03-26", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("vaulthome-x4", result.getJSONObject("serverInfo").getString("name"))
    }

    @Test fun unknownProtocolVersionFallsBackTo20250326() {
        val r = init("1999-01-01")
        assertEquals("2025-03-26", JSONObject(r.body).getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun callsRequireAValidSession() {
        val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        assertEquals(400, post(list, session = null).code)
        assertEquals(404, post(list, session = "nope").code)
        init()
        val ok = post(list)
        assertEquals(200, ok.code)
        assertEquals("sess-1", ok.headers["Mcp-Session-Id"])
        assertEquals("2025-03-26", ok.headers["MCP-Protocol-Version"])
        assertEquals(400, post(list, version = "1999-01-01").code)
    }

    @Test fun notificationIsAccepted() {
        init()
        val r = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.code)
        assertEquals("", r.body)
    }

    @Test fun toolsListHasShowTextAndPanelStatus() {
        init()
        val tools = JSONObject(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").body)
            .getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("show_text", "panel_status")))
        val show = (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "show_text" }
        assertEquals("text", show.getJSONObject("inputSchema").getJSONArray("required").getString(0))
    }

    @Test fun showTextSendsMarkdownUnchangedAndReportsPages() {
        init()
        val md = "---\ntags: [x]\n---\n# Título\n\n- bullet [[Note|label]]  \n"
        val result = call("show_text", JSONObject().put("text", md))
        assertFalse(result.getBoolean("isError"))
        assertTrue(text(result), text(result).contains("3 page(s)"))
        assertTrue(text(result).contains("pages:3"))
        assertEquals(listOf(md), gw.sent)
    }

    @Test fun showTextWithoutPagesIsNotSuccess() {
        gw.reply = { X4ShowResult("ok", null, false, null) }
        init()
        val result = call("show_text", JSONObject().put("text", "# hi"))
        assertTrue(result.getBoolean("isError"))
        assertTrue(text(result).contains("pages:N"))
    }

    @Test fun showTextRefusesVaultKeepAndTooLong() {
        init()
        val keep = call("show_text", JSONObject().put("text", "see [[VaultKeep/secret]]"))
        assertTrue(keep.getBoolean("isError"))
        assertTrue(text(keep).contains("VaultKeep"))
        val keepPath = call("show_text", JSONObject().put("text", "# fine").put("path", "VaultKeep/a.md"))
        assertTrue(keepPath.getBoolean("isError"))
        val long = call("show_text", JSONObject().put("text", "a".repeat(32_001)))
        assertTrue(long.getBoolean("isError"))
        assertTrue(text(long).contains("Too long"))
        val missing = call("show_text", JSONObject())
        assertTrue(missing.getBoolean("isError"))
        assertTrue(gw.sent.isEmpty())
    }

    @Test fun showBitmapIsRejected() {
        init()
        val r = call("show_bitmap", JSONObject().put("bitmap", "AAAA"))
        assertTrue(r.getBoolean("isError"))
        assertTrue(text(r).contains("BLE"))
        assertTrue(gw.sent.isEmpty())
    }

    @Test fun panelStatusReportsDetectedDevice() {
        init()
        val r = call("panel_status")
        assertFalse(r.getBoolean("isError"))
        assertTrue(text(r).contains("reachable: true"))
        assertTrue(text(r).contains("C2:45:6B:2D:4E:FC"))
        assertTrue(text(r).contains("transport: ble"))
    }

    @Test fun panelStatusUnreachableSaysWakeIt() {
        gw.found = null
        init()
        val r = call("panel_status")
        assertTrue(r.getBoolean("isError"))
        val t = text(r)
        assertTrue(t, t.contains("reachable: false"))
        assertTrue(t.contains("press any key", ignoreCase = true))
        assertFalse(t.contains("192.168"))
        val s = call("show_text", JSONObject().put("text", "# hi"))
        assertTrue(s.getBoolean("isError"))
        assertTrue(text(s).contains("unreachable"))
    }

    @Test fun unknownToolAndMethodAreJsonRpcErrors() {
        init()
        val tool = JSONObject(post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"vault-home-panel","arguments":{}}}""").body)
        assertEquals(-32602, tool.getJSONObject("error").getInt("code"))
        val m = JSONObject(post("""{"jsonrpc":"2.0","id":6,"method":"nope"}""").body)
        assertEquals(-32601, m.getJSONObject("error").getInt("code"))
        assertEquals(400, post("not json").code)
    }

    @Test fun wrongPathAndGetAreRefused() {
        assertEquals(404, handler.handle(McpHttpRequest("POST", "/other", emptyMap(), "{}")).code)
        assertEquals(405, handler.handle(McpHttpRequest("GET", "/mcp")).code)
    }

    @Test fun deleteEndsSession() {
        init()
        assertEquals(200, handler.handle(McpHttpRequest("DELETE", "/mcp", mapOf("mcp-session-id" to "sess-1"))).code)
        assertEquals(404, post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").code)
    }

    @Test fun overlappingCallsNeverInterleaveBle() = runBlocking {
        val slow = FakeGateway(delayMs = 50)
        var n = 0
        val h = X4McpHandler(slow, { X4Settings() }, newSessionId = { "s${n++}" })
        h.handle(McpHttpRequest("POST", "/mcp", emptyMap(), """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}"""))
        val calls = (1..4).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                val name = if (i % 2 == 0) "panel_status" else "show_text"
                h.handleSuspend(McpHttpRequest("POST", "/mcp", mapOf("mcp-session-id" to "s0"),
                    """{"jsonrpc":"2.0","id":$i,"method":"tools/call","params":{"name":"$name","arguments":{"text":"# $i"}}}"""))
            }
        }
        calls.awaitAll().forEach { assertEquals(200, it.code) }
        assertEquals(1, slow.maxActive)
    }

    @Test fun listenUrlsPutTailscaleFirstAndAddLocalhost() {
        val urls = X4McpHttpServer.listenUrls(8765, listOf("192.168.1.40", "100.101.102.103"))
        assertEquals("http://100.101.102.103:8765/mcp", urls[0].url)
        assertEquals("Tailscale", urls[0].kind)
        assertEquals("http://192.168.1.40:8765/mcp", urls[1].url)
        assertEquals("http://127.0.0.1:8765/mcp", urls.last().url)
        assertFalse(X4McpHttpServer.isTailscale("100.200.1.1"))
        assertEquals(8765, X4McpHttpServer.parsePort(" 8765 "))
        assertEquals(null, X4McpHttpServer.parsePort("80"))
        assertEquals(null, X4McpHttpServer.parsePort("70000"))
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** The phone-side client written for the X4 firmware talks to this server unchanged (one keep-alive connection). */
    @Test fun existingMcpClientWorksAgainstServerOverHttp() = runBlocking {
        val port = freePort()
        val server = X4McpHttpServer(X4McpHandler(gw, { X4Settings() }), port, bindHost = "127.0.0.1")
        server.start()
        try {
            val r = X4McpClient(probeTimeoutMs = 2_000, connectTimeoutMs = 2_000, sendReadTimeoutMs = 5_000)
                .showText("http://127.0.0.1:$port", "# Hello X4\n\nŻółw")
            assertEquals(3, r.pages)
            assertNotNull(r.status)
            assertEquals(listOf("# Hello X4\n\nŻółw"), gw.sent)
        } finally {
            server.stop()
        }
    }

    @Test fun rawHttpSequentialRequestsOnOneConnection() {
        val port = freePort()
        val server = X4McpHttpServer(X4McpHandler(gw, { X4Settings() }, newSessionId = { "abc" }), port, bindHost = "127.0.0.1")
        server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5_000
                val input = BufferedInputStream(s.getInputStream())
                fun send(body: String, session: Boolean): Pair<Int, String> {
                    val b = body.toByteArray()
                    val head = "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                        (if (session) "Mcp-Session-Id: abc\r\nMCP-Protocol-Version: 2025-03-26\r\n" else "") +
                        "Content-Length: ${b.size}\r\n\r\n"
                    s.getOutputStream().write(head.toByteArray() + b)
                    var status = 0; var len = 0; var first = true
                    while (true) {
                        val line = readLine(input)
                        if (first) { status = line.split(' ')[1].toInt(); first = false; continue }
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                    }
                    val buf = ByteArray(len); var off = 0
                    while (off < len) off += input.read(buf, off, len - off)
                    return status to String(buf)
                }
                assertEquals(200, send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""", false).first)
                val show = send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"show_text","arguments":{"text":"# a"}}}""", true)
                assertEquals(200, show.first)
                assertTrue(show.second.contains("pages:3"))
                val status = send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"panel_status","arguments":{}}}""", true)
                assertTrue(status.second.contains("last: pages:3"))
                val batch = send("""[{"jsonrpc":"2.0","id":4,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""", true)
                assertEquals(1, JSONArray(batch.second).length())
            }
        } finally {
            server.stop()
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
