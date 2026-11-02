package com.qcenaman.nassaver.core.dav

import com.qcenaman.nassaver.core.fs.FileOps
import com.qcenaman.nassaver.core.fs.Mime
import com.qcenaman.nassaver.core.fs.VirtualFs
import com.qcenaman.nassaver.core.fs.VirtualFs.Node
import com.qcenaman.nassaver.core.http.Http
import com.qcenaman.nassaver.core.http.HttpException
import com.qcenaman.nassaver.core.http.Request
import com.qcenaman.nassaver.core.http.Response
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV class 1 and 2 (locks are accepted but not enforced) over the virtual drive tree. */
class WebDav(
    private val fs: VirtualFs,
    private val prefix: String = "/dav",
    private val log: (String) -> Unit = {},
) {
    fun handle(req: Request, path: String): Response = when (req.method) {
        "OPTIONS" -> options()
        "PROPFIND" -> propfind(req, path)
        "PROPPATCH" -> proppatch(req, path)
        "GET", "HEAD" -> get(req, path)
        "PUT" -> put(req, path)
        "DELETE" -> delete(req, path)
        "MKCOL" -> mkcol(req, path)
        "MOVE" -> moveOrCopy(req, path, move = true)
        "COPY" -> moveOrCopy(req, path, move = false)
        "LOCK" -> lock(req, path)
        "UNLOCK" -> Response.empty(204)
        else -> Response.text(405, "method not allowed\n").header("Allow", ALLOW)
    }

    fun options(): Response = Response.empty(200)
        .header("DAV", "1, 2").header("MS-Author-Via", "DAV").header("Allow", ALLOW)

    private fun item(path: String): Node.Item = when (val n = fs.resolve(path)) {
        is Node.Item -> n
        Node.Root -> throw HttpException(403, "the top level only lists the drives")
        null -> throw HttpException(404, "not found")
    }

    fun href(node: Node, dir: Boolean): String = when (node) {
        Node.Root -> "$prefix/"
        is Node.Item -> prefix + Http.encodePath(node.path) + if (dir) "/" else ""
    }

    // ---- PROPFIND / PROPPATCH -------------------------------------------------------------------

    private fun propfind(req: Request, path: String): Response {
        req.bodyText(64 * 1024) // which properties were asked for doesn't matter: all are returned
        val depth = if (req.header("depth")?.trim() == "0") 0 else 1
        val node = fs.resolve(path) ?: return Response.text(404, "not found\n")
        val xml = StringBuilder(XML_HEAD).append("<D:multistatus xmlns:D=\"DAV:\">")
        when (node) {
            Node.Root -> {
                rootEntry(xml)
                if (depth == 1) fs.drives().forEach { entry(xml, Node.Item(it, it.root, ""), it.root) }
            }
            is Node.Item -> {
                if (!node.file.exists()) return Response.text(404, "not found\n")
                entry(xml, node, node.drive.root)
                if (depth == 1 && node.file.isDirectory) {
                    val children = node.file.listFiles() ?: return Response.text(403, "cannot read folder\n")
                    for (c in children) if (!FileOps.isHidden(c.name)) entry(xml, fs.child(node, c.name), node.drive.root)
                }
            }
        }
        xml.append("</D:multistatus>")
        return Response.xml(207, xml.toString())
    }

    private fun rootEntry(xml: StringBuilder) {
        xml.append("<D:response><D:href>").append(esc(href(Node.Root, true))).append("</D:href><D:propstat><D:prop>")
            .append("<D:displayname>NasSaver</D:displayname><D:resourcetype><D:collection/></D:resourcetype>")
            .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    private fun entry(xml: StringBuilder, node: Node.Item, driveRoot: File) {
        val f = node.file
        val dir = f.isDirectory
        val modified = f.lastModified()
        xml.append("<D:response><D:href>").append(esc(href(node, dir))).append("</D:href><D:propstat><D:prop>")
        xml.append("<D:displayname>").append(esc(node.name)).append("</D:displayname>")
        xml.append("<D:getlastmodified>").append(Http.httpDate(modified)).append("</D:getlastmodified>")
        xml.append("<D:creationdate>").append(Http.isoDate(modified)).append("</D:creationdate>")
        if (dir) {
            xml.append("<D:resourcetype><D:collection/></D:resourcetype>")
            val free = driveRoot.usableSpace
            xml.append("<D:quota-available-bytes>").append(free).append("</D:quota-available-bytes>")
            xml.append("<D:quota-used-bytes>").append((driveRoot.totalSpace - driveRoot.freeSpace).coerceAtLeast(0)).append("</D:quota-used-bytes>")
        } else {
            xml.append("<D:resourcetype/>")
            xml.append("<D:getcontentlength>").append(f.length()).append("</D:getcontentlength>")
            xml.append("<D:getcontenttype>").append(esc(Mime.of(f.name))).append("</D:getcontenttype>")
            xml.append("<D:getetag>").append(esc(etag(f))).append("</D:getetag>")
        }
        xml.append(SUPPORTED_LOCK).append("<D:lockdiscovery/>")
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    /** Clients (Windows, macOS) set file times through PROPPATCH; accept every property without storing it. */
    private fun proppatch(req: Request, path: String): Response {
        val body = req.bodyText(256 * 1024)
        val node = fs.resolve(path) ?: return Response.text(404, "not found\n")
        val names = runCatching { propNames(body) }.getOrDefault(emptyList())
        val xml = StringBuilder(XML_HEAD).append("<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
            .append(esc(href(node, (node as? Node.Item)?.file?.isDirectory ?: true))).append("</D:href><D:propstat><D:prop>")
        names.forEachIndexed { i, (ns, local) -> xml.append("<p$i:$local xmlns:p$i=\"").append(esc(ns)).append("\"/>") }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
        return Response.xml(207, xml.toString())
    }

    private fun propNames(body: String): List<Pair<String, String>> {
        if (body.isBlank()) return emptyList()
        if (body.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(body.byteInputStream())
        val out = mutableListOf<Pair<String, String>>()
        val props = doc.getElementsByTagNameNS("DAV:", "prop")
        for (i in 0 until props.length) {
            val children = props.item(i).childNodes
            for (j in 0 until children.length) {
                val c = children.item(j) as? Element ?: continue
                out += (c.namespaceURI ?: "") to (c.localName ?: c.tagName)
            }
        }
        return out
    }

    // ---- GET / HEAD ----------------------------------------------------------------------------

    private fun get(req: Request, path: String): Response {
        val node = fs.resolve(path) ?: return Response.text(404, "not found\n")
        if (node is Node.Root || (node as Node.Item).file.isDirectory) {
            val p = if (node is Node.Item) node.path else "/"
            return Response.redirect("/#" + Http.encodePath(p))
        }
        val f = node.file
        if (!f.isFile) return Response.text(404, "not found\n")
        val length = f.length()
        val tag = etag(f)
        val headers = linkedMapOf(
            "Content-Type" to Mime.of(f.name),
            "Accept-Ranges" to "bytes",
            "ETag" to tag,
            "Last-Modified" to Http.httpDate(f.lastModified()),
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "private, max-age=0, must-revalidate",
        )
        if (req.query.containsKey("download")) {
            headers["Content-Disposition"] = "attachment; filename*=UTF-8''" +
                URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        }
        if (req.header("if-none-match") == tag) return Response(304, headers = headers)
        val range = req.header("range")?.let { parseRange(it, length) }
        if (range == RANGE_INVALID) {
            return Response.text(416, "range not satisfiable\n").header("Content-Range", "bytes */$length")
        }
        if (range != null && (req.header("if-range") == null || req.header("if-range") == tag)) {
            val (start, end) = range
            headers["Content-Range"] = "bytes $start-$end/$length"
            return Response(206, Response.Body.Stream(end - start + 1) { f.inputStream().also { it.skipFully(start) } }, headers)
        }
        return Response(200, Response.Body.Stream(length) { f.inputStream() }, headers)
    }

    // ---- PUT / DELETE / MKCOL ------------------------------------------------------------------

    private fun put(req: Request, path: String): Response {
        val node = item(path)
        if (node.isDriveRoot) return Response.text(405, "cannot replace a drive\n")
        val target = node.file
        if (target.isDirectory) return Response.text(405, "a folder has that name\n")
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) return Response.text(409, "parent folder missing\n")
        val existed = target.exists()
        val temp = File(parent, FileOps.TEMP_PREFIX + UUID.randomUUID())
        var size = 0L
        try {
            req.acceptBody().let { body ->
                temp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
        } catch (e: IOException) {
            temp.delete()
            if (isNoSpace(e)) return Response.text(507, "drive is full\n")
            throw e
        }
        if (existed && !target.delete()) {
            temp.delete()
            return Response.text(409, "cannot replace ${target.name}\n")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return Response.text(500, "cannot save ${target.name}\n")
        }
        log("upload ${node.path} (${size} bytes) from ${req.remote}")
        return Response.empty(if (existed) 204 else 201)
    }

    private fun delete(req: Request, path: String): Response {
        val node = item(path)
        if (node.isDriveRoot) return Response.text(403, "cannot delete a drive\n")
        if (!node.file.exists()) return Response.text(404, "not found\n")
        if (!FileOps.deleteTree(node.file)) return Response.text(500, "could not delete everything\n")
        log("delete ${node.path} from ${req.remote}")
        return Response.empty(204)
    }

    private fun mkcol(req: Request, path: String): Response {
        if (req.bodyText(1024).isNotEmpty()) return Response.text(415, "MKCOL takes no body\n")
        val node = fs.resolve(path) ?: return Response.text(409, "no such drive\n")
        if (node !is Node.Item || node.file.exists()) return Response.text(405, "already exists\n")
        if (node.file.parentFile?.isDirectory != true) return Response.text(409, "parent folder missing\n")
        if (!node.file.mkdir()) return Response.text(500, "cannot create folder\n")
        log("new folder ${node.path} from ${req.remote}")
        return Response.empty(201)
    }

    // ---- MOVE / COPY ---------------------------------------------------------------------------

    private fun moveOrCopy(req: Request, path: String, move: Boolean): Response {
        val src = item(path)
        if (src.isDriveRoot) return Response.text(403, "cannot ${if (move) "move" else "copy"} a drive\n")
        if (!src.file.exists()) return Response.text(404, "not found\n")
        val destHeader = req.header("destination") ?: return Response.text(400, "Destination header missing\n")
        val destPath = Http.decodePath(destHeader.replaceFirst(Regex("^https?://[^/]*"), "").substringBefore('?'))
            ?: return Response.text(400, "bad Destination\n")
        if (destPath != prefix && !destPath.startsWith("$prefix/")) return Response.text(502, "Destination is not on this server\n")
        val dest = fs.resolve(destPath.removePrefix(prefix)) as? Node.Item ?: return Response.text(409, "bad Destination\n")
        if (dest.isDriveRoot) return Response.text(403, "cannot replace a drive\n")
        if (FileOps.isInside(dest.file, src.file)) return Response.text(403, "cannot put a folder inside itself\n")
        if (dest.file.parentFile?.isDirectory != true) return Response.text(409, "destination folder missing\n")
        val overwrite = req.header("overwrite")?.trim()?.uppercase() != "F"
        val existed = dest.file.exists()
        if (existed) {
            if (!overwrite) return Response.text(412, "destination exists\n")
            if (!FileOps.deleteTree(dest.file)) return Response.text(500, "cannot replace destination\n")
        }
        try {
            if (move) FileOps.move(src.file, dest.file) else FileOps.copyTree(src.file, dest.file)
        } catch (e: IOException) {
            return if (isNoSpace(e)) Response.text(507, "drive is full\n") else Response.text(500, "${e.message}\n")
        }
        log("${if (move) "move" else "copy"} ${src.path} -> ${dest.path} from ${req.remote}")
        return Response.empty(if (existed) 204 else 201)
    }

    // ---- LOCK ----------------------------------------------------------------------------------

    private fun lock(req: Request, path: String): Response {
        req.bodyText(64 * 1024)
        val node = fs.resolve(path) ?: return Response.text(409, "no such drive\n")
        var status = 200
        if (node is Node.Item && !node.file.exists()) {
            if (node.file.parentFile?.isDirectory != true) return Response.text(409, "parent folder missing\n")
            node.file.createNewFile() // a lock on a new name creates an empty file (RFC 4918 9.10.4)
            status = 201
        }
        val token = "opaquelocktoken:" + UUID.randomUUID()
        val dir = (node as? Node.Item)?.file?.isDirectory ?: true
        val xml = XML_HEAD + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth><D:timeout>Second-3600</D:timeout>" +
            "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${esc(href(node, dir))}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        return Response.xml(status, xml).header("Lock-Token", "<$token>")
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun etag(f: File) = "\"" + f.length().toString(16) + "-" + f.lastModified().toString(16) + "\""

    private fun isNoSpace(e: IOException) = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true

    private fun java.io.InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) throw IOException("cannot seek")
            left -= s
        }
    }

    companion object {
        const val ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"
        private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        private const val SUPPORTED_LOCK = "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>"
        val RANGE_INVALID = -1L to -1L

        /** Parses a single "bytes=" range; null to send the whole file, [RANGE_INVALID] when unsatisfiable. */
        fun parseRange(header: String, length: Long): Pair<Long, Long>? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return null
            val ranges = spec.substring(6).split(',')
            if (ranges.size != 1) return null
            val r = ranges[0].trim()
            val dash = r.indexOf('-')
            if (dash < 0) return null
            val a = r.substring(0, dash).trim()
            val b = r.substring(dash + 1).trim()
            if (length == 0L) return RANGE_INVALID
            return when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) RANGE_INVALID else (length - minOf(n, length)) to (length - 1)
                }
                else -> {
                    val start = a.toLongOrNull() ?: return null
                    val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(length - 1)
                    if (start >= length || end < start) RANGE_INVALID else start to end
                }
            }
        }

        fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') append('?') else append(c)
            }
        }
    }
}
