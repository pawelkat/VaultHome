package com.vaulthome.app.x4.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP/1.1 server for the display MCP: `POST /mcp` with Content-Length (or chunked) bodies, keep-alive,
 * JSON replies. Several connections may be open; BLE work is serialised inside [X4McpHandler].
 * Listens on 0.0.0.0 so the phone's LAN and Tailscale (100.x) addresses both reach it.
 */
class X4McpHttpServer(
    private val handler: X4McpHandler,
    val port: Int,
    private val bindHost: String = "0.0.0.0",
    private val idleTimeoutMs: Int = 120_000,
    private val maxConnections: Int = 8,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = server?.isClosed == false

    /** Binds now (throws [IOException] if the port is taken) and accepts on a background thread. */
    @Synchronized
    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
        server = ss
        val workers = ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "x4-mcp-conn").apply { isDaemon = true }
        }
        pool = workers
        acceptThread = Thread({
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: IOException) { break }
                try {
                    workers.execute { serve(sock) }
                } catch (e: Exception) {
                    // Too many open connections: answer 503 and close.
                    runCatching {
                        write(sock.getOutputStream(), McpHttpResponse(503, "Too many connections", contentType = "text/plain"), close = true)
                        sock.close()
                    }
                }
            }
        }, "x4-mcp-accept").apply { isDaemon = true; start() }
        log("listening on $bindHost:$port")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = idleTimeoutMs
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                while (!s.isClosed) {
                    val req = try {
                        readRequest(input, output) ?: return
                    } catch (e: HttpError) {
                        write(output, McpHttpResponse(e.code, e.message ?: "Bad request", contentType = "text/plain"), close = true)
                        return
                    }
                    val resp = try {
                        handler.handle(req)
                    } catch (e: Exception) {
                        log("handler failed: ${e.message}")
                        McpHttpResponse(500, X4McpHandler.error(org.json.JSONObject.NULL, -32603, "Internal error: ${e.message}").toString())
                    }
                    val close = req.header("connection")?.equals("close", ignoreCase = true) == true
                    write(output, resp, close)
                    if (close) return
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: SocketException) {
            } catch (e: IOException) {
                log("connection error: ${e.message}")
            }
        }
    }

    private class HttpError(val code: Int, message: String) : IOException(message)

    companion object {
        /** 32 000 chars as JSON `\uXXXX` escapes is ~192 KiB; leave room. */
        const val MAX_BODY = 1024 * 1024
        private const val MAX_LINE = 8 * 1024

        /** Null when the client closed the connection cleanly between requests. */
        internal fun readRequest(input: InputStream, output: OutputStream? = null): McpHttpRequest? {
            var line = readLine(input) ?: return null
            while (line.isEmpty()) line = readLine(input) ?: return null // tolerate stray CRLF
            val parts = line.split(' ')
            if (parts.size < 3 || !parts[2].startsWith("HTTP/")) throw HttpError(400, "Bad request line")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val h = readLine(input) ?: throw HttpError(400, "Connection closed mid-headers")
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true && output != null) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
            }
            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                headers["content-length"] != null -> {
                    val n = headers["content-length"]!!.toIntOrNull() ?: throw HttpError(400, "Bad Content-Length")
                    if (n < 0 || n > MAX_BODY) throw HttpError(413, "Body too large (max $MAX_BODY bytes)")
                    readExactly(input, n)
                }
                else -> ByteArray(0)
            }
            return McpHttpRequest(parts[0], parts[1], headers, String(body, Charsets.UTF_8))
        }

        internal fun write(output: OutputStream, resp: McpHttpResponse, close: Boolean) {
            val body = resp.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ").append(resp.code).append(' ').append(reason(resp.code)).append("\r\n")
                if (body.isNotEmpty() || resp.code == 200) append("Content-Type: ").append(resp.contentType).append("\r\n")
                resp.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("Cache-Control: no-store\r\n")
                append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            413 -> "Payload Too Large"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > MAX_LINE) throw HttpError(400, "Header line too long")
            }
            return out.toString(Charsets.ISO_8859_1.name())
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) throw HttpError(400, "Body cut short")
                off += r
            }
            return buf
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = (readLine(input) ?: throw HttpError(400, "Body cut short"))
                    .substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "Bad chunk size")
                if (size == 0) { while (!readLine(input).isNullOrEmpty()) { }; break }
                if (out.size() + size > MAX_BODY) throw HttpError(413, "Body too large (max $MAX_BODY bytes)")
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }

        /** Phone IPv4 addresses (Tailscale 100.64/10 first), never a guessed hostname. */
        fun localIpv4(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun isTailscale(ip: String): Boolean {
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            return p.size == 4 && p[0] == 100 && p[1] in 64..127
        }

        data class ListenUrl(val url: String, val kind: String)

        /** URLs to show in Settings: Tailscale, then LAN/other, then localhost. */
        fun listenUrls(port: Int, ips: List<String>): List<ListenUrl> {
            val sorted = ips.distinct().sortedBy { if (isTailscale(it)) 0 else 1 }
            return sorted.map { ip ->
                ListenUrl("http://$ip:$port/mcp", if (isTailscale(ip)) "Tailscale" else "LAN")
            } + ListenUrl("http://127.0.0.1:$port/mcp", "this phone")
        }

        const val DEFAULT_PORT = 8765

        /** Unprivileged TCP port, or null. */
        fun parsePort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
    }
}
