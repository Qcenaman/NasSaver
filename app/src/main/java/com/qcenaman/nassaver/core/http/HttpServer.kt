package com.qcenaman.nassaver.core.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/** Small HTTP/1.1 server: keep-alive, chunked and 100-continue uploads, streamed responses. */
class HttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
    private val threads: Int = 24,
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    val isRunning: Boolean get() = socket?.isClosed == false && acceptThread?.isAlive == true
    val boundPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        stop()
        return try {
            val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
            val w = Executors.newFixedThreadPool(threads)
            socket = s
            workers = w
            acceptThread = thread(name = "http-accept", isDaemon = true) { acceptLoop(s, w) }
            true
        } catch (e: IOException) {
            onError("server could not open port $port: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun acceptLoop(s: ServerSocket, w: ExecutorService) {
        while (!s.isClosed) {
            val client = try { s.accept() } catch (_: IOException) { break }
            try { w.execute { serve(client) } } catch (_: RejectedExecutionException) { client.close() }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.tcpNoDelay = true
                it.soTimeout = IDLE_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream(), BUFFER)
                val output = BufferedOutputStream(it.getOutputStream(), BUFFER)
                val remote = (it.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
                while (exchange(input, output, remote)) Unit
            }
        } catch (_: IOException) {
        }
    }

    /** Handles one request; returns whether the connection can carry another. */
    private fun exchange(input: InputStream, output: OutputStream, remote: String): Boolean {
        var line = Http.readLine(input) ?: return false
        while (line.isEmpty()) line = Http.readLine(input) ?: return false
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            write(output, Response.text(400, "bad request\n"), "GET", keepAlive = false)
            return false
        }
        val (method, target, version) = parts
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = Http.readLine(input) ?: return false
            if (h.isEmpty()) break
            if (++count > 100) {
                write(output, Response.text(431, "too many headers\n"), method, keepAlive = false)
                return false
            }
            val i = h.indexOf(':')
            if (i <= 0) continue
            val key = h.substring(0, i).trim().lowercase(Locale.ROOT)
            val value = h.substring(i + 1).trim()
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }

        val pathAndQuery = target.replaceFirst(Regex("^https?://[^/]*"), "").ifEmpty { "/" }
        val path = Http.decodePath(pathAndQuery.substringBefore('?'))
        if (path == null || !path.startsWith("/")) {
            write(output, Response.text(400, "bad path\n"), method, keepAlive = false)
            return false
        }
        val connection = headers["connection"]?.lowercase(Locale.ROOT)
        var keepAlive = if (version == "HTTP/1.1") connection?.contains("close") != true
        else connection?.contains("keep-alive") == true

        val chunked = headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        if (!chunked && headers.containsKey("content-length") && (length == null || length < 0)) {
            write(output, Response.text(400, "bad content length\n"), method, keepAlive = false)
            return false
        }
        val body: BodyStream = if (chunked) ChunkedInputStream(input) else LimitedInputStream(input, length ?: 0L)
        val expectContinue = headers["expect"]?.equals("100-continue", ignoreCase = true) == true
        var continued = false
        val request = Request(method, path, Http.parseQuery(pathAndQuery.substringAfter('?', "")), headers, body, remote) {
            if (expectContinue && !continued) {
                continued = true
                output.write(CONTINUE)
                output.flush()
            }
        }

        val response = try {
            handler(request)
        } catch (e: HttpException) {
            Response.text(e.status, "${e.message}\n")
        } catch (e: IOException) {
            keepAlive = false
            Response.text(500, "i/o error: ${e.message}\n")
        } catch (e: Exception) {
            onError("request failed: $method $path: ${e.javaClass.simpleName} ${e.message}")
            keepAlive = false
            Response.text(500, "internal error\n")
        }

        if (expectContinue && !continued) keepAlive = false
        else if (!body.finished && !runCatching { body.drain(DRAIN_LIMIT) }.getOrDefault(false)) keepAlive = false
        write(output, response, method, keepAlive)
        return keepAlive
    }

    private fun write(output: OutputStream, r: Response, method: String, keepAlive: Boolean) {
        val noBody = r.status in 100..199 || r.status == 204 || r.status == 304
        val length = when (val b = r.body) {
            is Response.Body.Bytes -> b.data.size.toLong()
            is Response.Body.Stream -> b.length
            Response.Body.None -> 0L
        }
        val head = StringBuilder("HTTP/1.1 ${r.status} ${Http.reason(r.status)}\r\n")
        head.append("Date: ").append(Http.httpDate(System.currentTimeMillis())).append("\r\n")
        head.append("Server: NasSaver\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        if (!noBody) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        output.write(head.toString().toByteArray())
        if (method != "HEAD" && !noBody) {
            when (val b = r.body) {
                is Response.Body.Bytes -> output.write(b.data)
                is Response.Body.Stream -> b.open().use { copyExactly(it, output, b.length) }
                Response.Body.None -> Unit
            }
        }
        output.flush()
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        val buf = ByteArray(BUFFER)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("file ended early")
            output.write(buf, 0, n)
            left -= n
        }
    }

    companion object {
        private const val BUFFER = 256 * 1024
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val DRAIN_LIMIT = 1L shl 20
        private val CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray()
    }
}
