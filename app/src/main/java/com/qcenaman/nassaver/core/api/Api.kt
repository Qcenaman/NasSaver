package com.qcenaman.nassaver.core.api

import com.qcenaman.nassaver.core.PcWaker
import com.qcenaman.nassaver.core.fs.DriveNames
import com.qcenaman.nassaver.core.fs.FileOps
import com.qcenaman.nassaver.core.fs.VirtualFs
import com.qcenaman.nassaver.core.fs.VirtualFs.Node
import com.qcenaman.nassaver.core.http.Request
import com.qcenaman.nassaver.core.http.Response
import java.io.File
import java.util.Locale

/** JSON endpoints for the web page: folder listings and search. Changes go through WebDAV. */
class Api(
    private val fs: VirtualFs,
    private val waker: PcWaker? = null,
    private val driveNames: DriveNames? = null,
    private val searchLimit: Int = 300,
    private val searchBudgetMs: Long = 8000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun handle(req: Request, sub: String): Response {
        if (sub == "/wol" && waker != null) return wol(req, waker)
        if (sub == "/drive-name" && driveNames != null) return driveName(req, driveNames)
        if (req.method != "GET") return Response.text(405, "method not allowed\n")
        return when (sub) {
            "/list" -> list(req.query["path"] ?: "/")
            "/search" -> search(req.query["path"] ?: "/", req.query["q"].orEmpty())
            else -> Response.text(404, "not found\n")
        }
    }

    private fun wol(req: Request, waker: PcWaker): Response = when (req.method) {
        "GET" -> Response.json(200, "{\"mac\":" + (waker.mac?.let(Json::str) ?: "null") + "}")
        "POST" -> if (req.header("x-nassaver") == null) {
            // A browser won't add this header on a request sent from another site.
            Response.json(403, "{\"error\":\"forbidden\"}")
        } else when (val r = waker.wake(req.query["mac"])) {
            is PcWaker.Result.Sent -> Response.json(200, "{\"sent\":true,\"mac\":" + Json.str(r.mac) + "}")
            PcWaker.Result.NoMac -> Response.json(409, "{\"error\":\"no computer saved yet\"}")
            PcWaker.Result.BadMac -> Response.json(400, "{\"error\":\"that is not a MAC address\"}")
            PcWaker.Result.NoNetwork -> Response.json(503, "{\"error\":\"the TV has no network\"}")
        }
        else -> Response.text(405, "method not allowed\n")
    }

    private fun driveName(req: Request, names: DriveNames): Response {
        if (req.method != "POST") return Response.text(405, "method not allowed\n")
        if (req.header("x-nassaver") == null) return Response.json(403, "{\"error\":\"forbidden\"}")
        val error = names.rename(req.query["drive"].orEmpty(), req.query["name"].orEmpty())
            ?: return run { fs.invalidate(); Response.json(200, "{\"ok\":true}") }
        return Response.json(400, "{\"error\":" + Json.str(error) + "}")
    }

    private fun list(path: String): Response {
        val node = fs.resolve(path) ?: return Response.json(404, "{\"error\":\"not found\"}")
        val sb = StringBuilder("{\"path\":").append(Json.str(pathOf(node))).append(",\"entries\":[")
        when (node) {
            Node.Root -> fs.drives().forEachIndexed { i, d ->
                if (i > 0) sb.append(',')
                sb.append("{\"name\":").append(Json.str(d.name)).append(",\"dir\":true,\"drive\":true")
                    .append(",\"size\":0,\"mtime\":").append(d.root.lastModified())
                    .append(",\"free\":").append(d.root.usableSpace)
                    .append(",\"total\":").append(d.root.totalSpace).append('}')
            }
            is Node.Item -> {
                if (!node.file.isDirectory) return Response.json(400, "{\"error\":\"not a folder\"}")
                val files = node.file.listFiles() ?: return Response.json(403, "{\"error\":\"cannot read folder\"}")
                val sorted = files.filter { !FileOps.isHidden(it.name) }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                sorted.forEachIndexed { i, f ->
                    if (i > 0) sb.append(',')
                    entry(sb, f.name, f)
                }
                sb.append("],\"free\":").append(node.drive.root.usableSpace)
                    .append(",\"total\":").append(node.drive.root.totalSpace).append('}')
                return Response.json(200, sb.toString())
            }
        }
        sb.append("]}")
        return Response.json(200, sb.toString())
    }

    private fun search(path: String, q: String): Response {
        val words = q.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return Response.json(400, "{\"error\":\"empty search\"}")
        val start = fs.resolve(path) ?: return Response.json(404, "{\"error\":\"not found\"}")
        val queue = ArrayDeque<Node.Item>()
        when (start) {
            Node.Root -> fs.drives().forEach { queue.add(Node.Item(it, it.root, "")) }
            is Node.Item -> queue.add(start)
        }
        val deadline = now() + searchBudgetMs
        val results = StringBuilder()
        var found = 0
        var scanned = 0
        var truncated = false
        loop@ while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = dir.file.listFiles() ?: continue
            for (f in children) {
                if (FileOps.isHidden(f.name)) continue
                scanned++
                val child = fs.child(dir, f.name)
                val name = f.name.lowercase(Locale.ROOT)
                if (words.all { name.contains(it) }) {
                    if (found == searchLimit) { truncated = true; break@loop }
                    if (found > 0) results.append(',')
                    entry(results, f.name, f, child.path)
                    found++
                }
                if (f.isDirectory) queue.add(child)
            }
            if (now() > deadline) { truncated = queue.isNotEmpty(); break }
        }
        return Response.json(
            200,
            "{\"results\":[$results],\"truncated\":$truncated,\"scanned\":$scanned}",
        )
    }

    private fun entry(sb: StringBuilder, name: String, f: File, path: String? = null) {
        val dir = f.isDirectory
        sb.append("{\"name\":").append(Json.str(name))
        if (path != null) sb.append(",\"path\":").append(Json.str(path))
        sb.append(",\"dir\":").append(dir)
            .append(",\"size\":").append(if (dir) 0 else f.length())
            .append(",\"mtime\":").append(f.lastModified()).append('}')
    }

    private fun pathOf(node: Node) = when (node) {
        Node.Root -> "/"
        is Node.Item -> node.path
    }
}

object Json {
    fun str(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}
