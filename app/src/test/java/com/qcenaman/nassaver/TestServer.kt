package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.PcWaker
import com.qcenaman.nassaver.core.Router
import com.qcenaman.nassaver.core.api.Api
import com.qcenaman.nassaver.core.auth.Auth
import com.qcenaman.nassaver.core.dav.WebDav
import com.qcenaman.nassaver.core.fs.Drive
import com.qcenaman.nassaver.core.fs.DriveNames
import com.qcenaman.nassaver.core.fs.VirtualFs
import com.qcenaman.nassaver.core.http.HttpServer
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.util.Base64

/** A real server on a free port over two temp "drives", plus a raw-socket client. */
class TestServer(root: File, withLogin: Boolean = true) : AutoCloseable {
    val driveA = File(root, "a").apply { mkdirs() }
    val driveB = File(root, "b").apply { mkdirs() }
    val logLines = mutableListOf<String>()
    private val store = object : Auth.Store {
        var saved: Auth.Login? = null
        override fun load() = saved
        override fun save(login: Auth.Login?) { saved = login }
    }
    val auth = Auth(store, iterations = 1000).also { if (withLogin) it.setLogin(USER, PASS) }
    val packets = mutableListOf<ByteArray>()
    private val macStore = object : PcWaker.Store {
        var mac: String? = null
        override fun load() = mac
        override fun save(mac: String) { this.mac = mac }
    }
    private val nameStore = object : DriveNames.Store {
        var names: Map<String, String> = emptyMap()
        override fun load() = names
        override fun save(names: Map<String, String>) { this.names = names }
    }
    private val driveNames = DriveNames({ listOf(Drive("Drive A", driveA), Drive("Drive B", driveB)) }, nameStore)
    private val fs = VirtualFs(driveNames, cacheMs = 0)
    private val router = Router(
        auth, WebDav(fs, log = { synchronized(logLines) { logLines += it } }), Api(fs, PcWaker(macStore) { packets += it; 1 }, driveNames),
        page = { "<html>ui</html>".toByteArray() }, status = { "<html>status</html>" },
    )
    private val server = HttpServer(0, router::handle).also { check(it.start()) }
    val port get() = server.boundPort

    override fun close() = server.stop()

    data class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = body.decodeToString()
        fun header(name: String) = headers[name.lowercase()]
    }

    fun req(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        login: Boolean = true,
    ): Resp = Socket("127.0.0.1", port).use { s ->
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n")
        if (login) sb.append("Authorization: ").append(basic(USER, PASS)).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        s.getOutputStream().write(sb.toString().toByteArray())
        body?.let { s.getOutputStream().write(it) }
        parse(s.getInputStream().readBytes())
    }

    companion object {
        const val USER = "tv"
        const val PASS = "secret1"

        fun basic(user: String, pass: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

        fun parse(raw: ByteArray): Resp {
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val head = text.substring(0, split).split("\r\n")
            val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            return Resp(head[0].split(' ')[1].toInt(), headers, raw.copyOfRange(split + 4, raw.size))
        }

        /** Reads one response with a Content-Length from a kept-alive connection. */
        fun readOne(input: InputStream): Resp {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                check(b >= 0) { "connection closed" }
                head.append(b.toChar())
            }
            val length = Regex("(?i)content-length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var off = 0
            while (off < length) off += input.read(body, off, length - off).also { check(it > 0) }
            return parse((head.toString().toByteArray(Charsets.ISO_8859_1)) + body)
        }
    }
}
