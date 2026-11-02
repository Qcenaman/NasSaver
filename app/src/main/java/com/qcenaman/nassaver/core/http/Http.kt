package com.qcenaman.nassaver.core.http

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HttpException(val status: Int, message: String) : Exception(message)

class Request(
    val method: String,
    /** Percent-decoded path without the query, e.g. "/dav/USB drive/Movies". */
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: InputStream,
    val remote: String,
    private val sendContinue: () -> Unit = {},
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    /** Tells a client that sent "Expect: 100-continue" to go ahead, then returns the body. */
    fun acceptBody(): InputStream {
        sendContinue()
        return body
    }

    fun bodyText(max: Int = 1 shl 20): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val input = acceptBody()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw HttpException(413, "request body too large")
        }
        return out.toString("UTF-8")
    }
}

class Response(
    val status: Int,
    val body: Body = Body.None,
    val headers: MutableMap<String, String> = linkedMapOf(),
) {
    sealed class Body {
        object None : Body()
        class Bytes(val data: ByteArray) : Body()
        class Stream(val length: Long, val open: () -> InputStream) : Body()
    }

    fun header(name: String, value: String) = apply { headers[name] = value }

    companion object {
        fun empty(status: Int) = Response(status)
        fun text(status: Int, text: String) = typed(status, "text/plain; charset=utf-8", text)
        fun html(status: Int, html: String) = typed(status, "text/html; charset=utf-8", html)
        fun json(status: Int, json: String) = typed(status, "application/json; charset=utf-8", json)
        fun xml(status: Int, xml: String) = typed(status, "application/xml; charset=utf-8", xml)
        fun redirect(location: String) = Response(302).header("Location", location)
        private fun typed(status: Int, type: String, s: String) =
            Response(status, Body.Bytes(s.toByteArray())).header("Content-Type", type)
    }
}

/** A request body that knows when it has been read to the end. */
abstract class BodyStream : InputStream() {
    abstract val finished: Boolean

    /** Reads and discards what is left, up to [limit] bytes; true when the body ended. */
    fun drain(limit: Long): Boolean {
        val buf = ByteArray(8192)
        var total = 0L
        while (!finished) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return false
        }
        return finished
    }

    override fun close() {} // never close the connection's stream
}

class LimitedInputStream(private val src: InputStream, private var remaining: Long) : BodyStream() {
    override val finished get() = remaining <= 0L

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read()
        if (b < 0) throw EOFException("client closed the connection")
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (len == 0) return 0
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("client closed the connection")
        remaining -= n
        return n
    }
}

/** Decodes a "Transfer-Encoding: chunked" request body. */
class ChunkedInputStream(private val src: InputStream) : BodyStream() {
    private var left = 0L
    private var done = false
    private var needCrlf = false
    override val finished get() = done

    private fun nextChunk() {
        if (needCrlf) {
            if (Http.readLine(src) != "") throw IOException("malformed chunk")
            needCrlf = false
        }
        val line = Http.readLine(src) ?: throw EOFException("client closed the connection")
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
        if (size == 0L) {
            while (true) {
                val trailer = Http.readLine(src) ?: throw EOFException("client closed the connection")
                if (trailer.isEmpty()) break
            }
            done = true
        } else {
            left = size
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (len == 0) return 0
        if (left == 0L) {
            nextChunk()
            if (done) return -1
        }
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw EOFException("client closed the connection")
        left -= n
        if (left == 0L) needCrlf = true
        return n
    }
}

object Http {
    fun readLine(input: InputStream, max: Int = 16 * 1024): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= max) throw IOException("line too long")
            sb.append(b.toChar()) // bytes as Latin-1; decodePath turns them back into UTF-8
        }
    }

    /** Percent-decodes a URL path as UTF-8; '+' stays a plus. Null when malformed. */
    fun decodePath(raw: String): String? {
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                out.write(raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null)
                i += 3
            } else {
                if (c.code < 256) out.write(c.code) else out.write(c.toString().toByteArray())
                i++
            }
        }
        val s = out.toString("UTF-8")
        return if (s.contains('\u0000')) null else s
    }

    /** Percent-encodes everything except unreserved characters and '/'. */
    fun encodePath(path: String): String {
        val sb = StringBuilder()
        for (b in path.toByteArray()) {
            val c = b.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code || c == '/'.code
            ) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        decodeForm(it.substringBefore('=')) to decodeForm(it.substringAfter('=', ""))
    }

    private fun decodeForm(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun httpDate(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun isoDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun reason(code: Int) = when (code) {
        100 -> "Continue"
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        207 -> "Multi-Status"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        412 -> "Precondition Failed"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        507 -> "Insufficient Storage"
        else -> "Status"
    }
}
