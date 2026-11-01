package com.vaulthome.app.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Result of `GET {base}/health` (the chat proxy forwards it to llama-server). */
sealed interface Health {
    data object Ok : Health
    /** llama-server answers 503 while it loads the model. */
    data class Loading(val detail: String) : Health
    data class Down(val reason: String) : Health
}

/** What the Ask path needs from Nitro; an interface so the orchestrator can be tested without sockets. */
interface NitroGateway {
    suspend fun health(baseUrl: String): Health
    suspend fun chat(baseUrl: String, messages: List<ChatMessage>): ChatReply
}

data class ChatMessage(val role: String, val content: String)
data class ChatReply(val content: String, val model: String?)

class NitroException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Plain `HttpURLConnection` client for Nitro's chat proxy (OpenAI-compatible; the proxy runs vault_search
 * server-side and returns a finished message, so no tool handling here). No API key header: the Nitro
 * server has none today, and a key must never be baked into the APK.
 */
class NitroClient(
    private val healthTimeoutMs: Int = 1_500,
    private val chatConnectTimeoutMs: Int = 3_000,
    private val chatReadTimeoutMs: Int = 300_000,
) : NitroGateway {

    override suspend fun health(baseUrl: String): Health = withContext(Dispatchers.IO) {
        val connection = try {
            open("$baseUrl/health", healthTimeoutMs, healthTimeoutMs)
        } catch (e: Exception) {
            return@withContext Health.Down(describe(e))
        }
        try {
            val code = connection.responseCode
            when (code) {
                200 -> Health.Ok
                503 -> Health.Loading(readError(connection).ifBlank { "Nitro is loading the model" })
                else -> Health.Down("Nitro /health returned HTTP $code")
            }
        } catch (e: Exception) {
            Health.Down(describe(e))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun chat(baseUrl: String, messages: List<ChatMessage>): ChatReply = withContext(Dispatchers.IO) {
        val body = ChatJson.request(messages).toByteArray(Charsets.UTF_8)
        val connection = try {
            open("$baseUrl/v1/chat/completions", chatConnectTimeoutMs, chatReadTimeoutMs)
        } catch (e: Exception) {
            throw NitroException(describe(e), e)
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = readError(connection)
                throw NitroException("Nitro chat returned HTTP $code" + if (detail.isNotBlank()) ": $detail" else "")
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ChatJson.parseReply(text)
        } catch (e: NitroException) {
            throw e
        } catch (e: Exception) {
            throw NitroException(describe(e), e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "VaultHome/0.1")
            setRequestProperty("Accept", "application/json")
        }

    private fun readError(connection: HttpURLConnection): String = runCatching {
        val raw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: raw.take(200)
    }.getOrDefault("")

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Nitro timed out"
        is java.net.ConnectException -> "Nitro refused the connection"
        is java.net.UnknownHostException -> "Nitro host not found"
        else -> {
            val msg = e.message.orEmpty()
            if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                "Plain http is only allowed to 192.168.1.18 / localhost (network security config)"
            } else {
                e.javaClass.simpleName + if (msg.isNotBlank()) ": $msg" else ""
            }
        }
    }
}

/** OpenAI chat JSON for llama-server. */
object ChatJson {
    fun request(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
        return JSONObject()
            .put("model", "nitro")
            .put("messages", array)
            .put("stream", false)
            .put("temperature", 0.3)
            .put("max_tokens", 1024)
            // Qwen3.x on llama-server: skip the thinking pass so the phone gets an answer sooner.
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .toString()
    }

    fun parseReply(json: String): ChatReply {
        val root = try { JSONObject(json) } catch (e: Exception) { throw NitroException("Nitro sent invalid JSON", e) }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw NitroException("Nitro reply has no choices")
        val message = choice.optJSONObject("message") ?: throw NitroException("Nitro reply has no message")
        val content = stripThinking(if (message.isNull("content")) "" else message.optString("content"))
        if (content.isBlank()) throw NitroException("Nitro returned an empty answer")
        val model = root.optString("model").takeIf { it.isNotBlank() }
        return ChatReply(content, model)
    }

    /** Drops `<think>…</think>` blocks some Qwen templates leave in `content`. */
    fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "").trim()
}
