package com.vaulthome.app.x4

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** X4McpClient against MockWebServer standing in for the X4 firmware (`src/bin/x4/mcp.rs`). */
class X4McpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val client = X4McpClient(probeTimeoutMs = 500, connectTimeoutMs = 500, sendReadTimeoutMs = 1_000)

    @Before fun start() {
        server = MockWebServer(); server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun stop() = server.shutdown()

    private fun rpc(id: Int, result: String, session: Boolean = true) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .apply { if (session) setHeader("Mcp-Session-Id", "a5c30001").setHeader("MCP-Protocol-Version", "2025-03-26") }
        .setHeader("Connection", "keep-alive")
        .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")

    private val initOk = """{"protocolVersion":"2025-03-26","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"x4-epaper","version":"0.1.0"}}"""
    private fun text(t: String, err: Boolean = false) =
        JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", t))).apply { if (err) put("isError", true) }.toString()
    private val status = "url: http://192.168.1.27/mcp\norientation: portrait 480x800\nrefreshing: false\npending: 0\nlast: x\nbitmap: 480x800 stride 60 bytes 48000 (1=white, 0=black, MSB left)\nstored: 3/24\nshown: 0"

    @Test fun showTextRunsWholeSessionOnOneConnection() = runBlocking {
        server.enqueue(rpc(1, initOk))
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Mcp-Session-Id", "a5c30001").setHeader("Content-Length", "0"))
        server.enqueue(rpc(2, text("Queued 3 page(s) on the X4 (480x800 portrait). Side up is the previous page, side down is the next.")))
        server.enqueue(rpc(3, text(status)))
        val md = "---\ntags: [x]\n---\n# Título\n\n- bullet [[Note|label]]\n"
        val r = client.showText(base, md)
        assertEquals(3, r.pages)
        assertFalse(r.cut)
        assertEquals(false, r.status?.refreshing)
        assertEquals("3/24", r.status?.stored)

        val init = server.takeRequest()
        val note = server.takeRequest()
        val show = server.takeRequest()
        val stat = server.takeRequest()
        // One TCP connection: request index on the connection keeps counting up.
        assertEquals(listOf(0, 1, 2, 3), listOf(init, note, show, stat).map { it.sequenceNumber })
        assertEquals("/mcp", init.path)
        assertEquals("POST", init.method)
        assertEquals("application/json", init.getHeader("Content-Type"))
        assertEquals("application/json", init.getHeader("Accept"))
        assertNull(init.getHeader("Mcp-Session-Id"))
        val initBody = JSONObject(init.body.readUtf8())
        assertEquals("initialize", initBody.getString("method"))
        assertEquals("2025-03-26", initBody.getJSONObject("params").getString("protocolVersion"))
        assertEquals("vault-home", initBody.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
        assertEquals("notifications/initialized", JSONObject(note.body.readUtf8()).getString("method"))
        for (r2 in listOf(note, show, stat)) {
            assertEquals("a5c30001", r2.getHeader("Mcp-Session-Id"))
            assertEquals("2025-03-26", r2.getHeader("MCP-Protocol-Version"))
        }
        val showBody = JSONObject(show.body.readUtf8())
        assertEquals("tools/call", showBody.getString("method"))
        assertEquals("show_text", showBody.getJSONObject("params").getString("name"))
        assertEquals(md, showBody.getJSONObject("params").getJSONObject("arguments").getString("text")) // unchanged
        assertEquals("panel_status", JSONObject(stat.body.readUtf8()).getJSONObject("params").getString("name"))
    }

    @Test fun cutReplyIsReported() = runBlocking {
        server.enqueue(rpc(1, initOk))
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Length", "0"))
        server.enqueue(rpc(2, text("Queued 24 page(s) on the X4 (480x800 portrait). Side up is the previous page, side down is the next. The note was cut at the page limit.")))
        server.enqueue(rpc(3, text(status)))
        val r = client.showText(base, "# long")
        assertEquals(24, r.pages)
        assertTrue(r.cut)
        assertTrue(X4Text.sent(r).contains("24 pages") && X4Text.sent(r).contains("cut"))
    }

    @Test fun toolErrorBecomesException() = runBlocking {
        server.enqueue(rpc(1, initOk))
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Length", "0"))
        server.enqueue(rpc(2, text("Text is too long (max 32000 characters).", err = true)))
        try { client.showText(base, "x"); fail() } catch (e: X4Exception) {
            assertEquals("X4: Text is too long (max 32000 characters).", e.message)
        }
    }

    @Test fun missingSessionHeaderFails() = runBlocking {
        server.enqueue(rpc(1, initOk, session = false))
        try { client.probe(base); fail() } catch (e: X4Exception) { assertEquals("X4 sent no Mcp-Session-Id", e.message) }
    }

    @Test fun probeReadsPanelStatus() = runBlocking {
        server.enqueue(rpc(1, initOk))
        server.enqueue(rpc(2, text(status)))
        val s = client.probe(base)
        assertEquals("http://192.168.1.27/mcp", s.url)
        assertEquals("0", s.shown)
        assertEquals(listOf(0, 1), listOf(server.takeRequest(), server.takeRequest()).map { it.sequenceNumber })
    }

    @Test fun probeTimesOutQuickly() = runBlocking {
        server.enqueue(rpc(1, initOk).setHeadersDelay(3, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        try { client.probe(base); fail() } catch (e: X4Exception) { assertTrue(e.message!!, e.message!!.contains("timed out")) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun refusedIsX4Exception() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try { client.probe("http://127.0.0.1:$port"); fail() } catch (e: X4Exception) { assertTrue(e.message!!, e.message!!.contains("refused")) }
    }

    @Test fun crowPanelAndPublicHostsAreRefusedWithoutConnecting() = runBlocking {
        for (url in listOf("http://192.168.1.26", "http://8.8.8.8", "https://192.168.1.27")) {
            try { client.probe(url); fail(url) } catch (e: X4Exception) { /* expected */ }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun chunkedRepliesAreParsed() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nMcp-Session-Id: 1\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        val r = HttpReply.read(raw.byteInputStream())
        assertEquals(200, r.code); assertEquals("hello", r.body); assertEquals("1", r.header("MCP-SESSION-ID"))
    }

    @Test fun jsonRpcErrorThrows() {
        try { McpJson.result("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unsupported protocol version"}}"""); fail() }
        catch (e: X4Exception) { assertEquals("X4 error: Unsupported protocol version", e.message) }
    }
}
