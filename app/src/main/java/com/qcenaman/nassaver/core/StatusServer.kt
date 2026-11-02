package com.qcenaman.nassaver.core

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

object Http {
    data class Request(val method: String, val path: String)

    fun parseRequestLine(line: String?): Request? {
        val parts = line?.trim()?.split(' ') ?: return null
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val path = parts[1].substringBefore('?')
        if (!path.startsWith('/')) return null
        return Request(parts[0], path)
    }

    fun statusText(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    fun response(code: Int, type: String, body: ByteArray, includeBody: Boolean = true): ByteArray {
        val head = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        return head.toByteArray() + if (includeBody) body else ByteArray(0)
    }
}

/** Tiny read-only HTTP server for the status page: "/" (HTML), "/log.txt" and "/health". */
class StatusServer(
    private val port: Int,
    private val pages: Pages,
    private val onError: (String) -> Unit = {},
) {
    interface Pages {
        fun html(): String
        fun log(): String
    }

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
            val w = Executors.newFixedThreadPool(4)
            socket = s
            workers = w
            acceptThread = thread(name = "status-accept", isDaemon = true) { acceptLoop(s, w) }
            true
        } catch (e: IOException) {
            onError("status page could not open port $port: ${e.message}")
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
            try { w.execute { handle(client) } } catch (_: RejectedExecutionException) { client.close() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                it.soTimeout = 5000
                val input = it.getInputStream().buffered()
                val requestLine = readLine(input)
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                }
                it.getOutputStream().apply { write(route(Http.parseRequestLine(requestLine))); flush() }
            }
        } catch (_: IOException) {
        }
    }

    fun route(req: Http.Request?): ByteArray {
        if (req == null) return Http.response(400, TEXT, "bad request\n".toByteArray())
        val head = req.method == "HEAD"
        if (req.method != "GET" && !head) return Http.response(405, TEXT, "method not allowed\n".toByteArray())
        return when (req.path) {
            "/", "/index.html" -> Http.response(200, HTML, pages.html().toByteArray(), !head)
            "/log.txt" -> Http.response(200, TEXT, pages.log().toByteArray(), !head)
            "/health" -> Http.response(200, TEXT, "ok\n".toByteArray(), !head)
            else -> Http.response(404, TEXT, "not found\n".toByteArray(), !head)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        const val HTML = "text/html; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"
    }
}
