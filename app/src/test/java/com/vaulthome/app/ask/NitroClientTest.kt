package com.vaulthome.app.ask

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

/** NitroClient against MockWebServer standing in for llama-server. */
class NitroClientTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before fun start() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After fun stop() = server.shutdown()

    private val client = NitroClient(healthTimeoutMs = 300, chatConnectTimeoutMs = 300, chatReadTimeoutMs = 1_000)

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun healthOk() = runBlocking {
        server.enqueue(json(200, """{"status":"ok"}"""))
        assertEquals(Health.Ok, client.health(base))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/health", req.path)
    }

    @Test fun health503IsLoading() = runBlocking {
        server.enqueue(json(503, """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}"""))
        assertEquals(Health.Loading("Loading model"), client.health(base))
    }

    @Test fun health500IsDown() = runBlocking {
        server.enqueue(json(500, "boom"))
        val h = client.health(base)
        assertTrue(h.toString(), h is Health.Down && h.reason.contains("500"))
    }

    @Test fun healthTimeoutIsDown() = runBlocking {
        server.enqueue(json(200, "{}").setHeadersDelay(2, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val h = client.health(base)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(Health.Down("Nitro health check timed out"), h)
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test fun healthRefusedIsDown() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens
        val h = client.health("http://127.0.0.1:$port")
        assertTrue(h.toString(), h is Health.Down)
    }

    @Test fun chatPostsOpenAiRequestAndParsesAnswer() = runBlocking {
        server.enqueue(
            json(200, """{"model":"qwen3.5-4b","choices":[{"index":0,"message":{"role":"assistant","content":"<think>hmm</think>\n PR #2 is open."}}]}"""),
        )
        val reply = client.chat(base, NitroPrompt.messages("What is open for VaultHome?"))
        assertEquals("PR #2 is open.", reply.content)
        assertEquals("qwen3.5-4b", reply.model)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("What is open for VaultHome?", messages.getJSONObject(0).getString("content"))
        assertFalse(body.has("tools")) // the proxy adds vault_search only when the client sends no tools
        // No API key header ever.
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun chatHttpErrorThrowsNitroException() = runBlocking {
        server.enqueue(json(500, """{"error":{"message":"context too long"}}"""))
        try {
            client.chat(base, listOf(ChatMessage("user", "x")))
            fail("expected NitroException")
        } catch (e: NitroException) {
            assertTrue(e.message!!, e.message!!.contains("500") && e.message!!.contains("context too long"))
        }
    }

    @Test fun chatEmptyContentThrows() = runBlocking {
        server.enqueue(json(200, """{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        try {
            client.chat(base, listOf(ChatMessage("user", "x")))
            fail("expected NitroException")
        } catch (e: NitroException) {
            assertEquals("Nitro returned an empty answer", e.message)
        }
    }

    @Test fun chatReadTimeoutThrows() = runBlocking {
        server.enqueue(json(200, "{}").setHeadersDelay(3, TimeUnit.SECONDS))
        try {
            client.chat(base, listOf(ChatMessage("user", "x")))
            fail("expected NitroException")
        } catch (e: NitroException) {
            assertEquals("Nitro answer timed out", e.message)
        }
    }

    @Test fun stripThinkingHandlesUnclosedPrefix() {
        assertEquals("answer", ChatJson.stripThinking("reasoning...</think>answer"))
        assertEquals("plain", ChatJson.stripThinking("  plain "))
    }
}
