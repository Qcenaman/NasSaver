package com.qcenaman.nassaver.core.fs

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/** A shared storage root: a USB drive, an SD card or the TV's own storage. */
data class Drive(val name: String, val root: File)

fun interface DriveProvider {
    fun drives(): List<Drive>
}

/** Maps virtual paths ("/USB drive/Movies/a.mkv") onto the mounted drives. */
class VirtualFs(
    private val provider: DriveProvider,
    private val cacheMs: Long = 2000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cached: List<Drive> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var valid = false

    fun drives(): List<Drive> {
        val t = now()
        if (!valid || t - cachedAt > cacheMs) {
            cached = provider.drives()
            cachedAt = t
            valid = true
        }
        return cached
    }

    fun invalidate() {
        valid = false
    }

    sealed class Node {
        object Root : Node()

        data class Item(val drive: Drive, val file: File, val rel: String) : Node() {
            val isDriveRoot get() = rel.isEmpty()
            val path get() = "/" + drive.name + if (rel.isEmpty()) "" else "/$rel"
            val name get() = if (rel.isEmpty()) drive.name else file.name
        }
    }

    /** Resolves a virtual path; null when it is malformed or names no mounted drive. */
    fun resolve(path: String): Node? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return Node.Root
        if (segments.any { it == "." || it == ".." }) return null
        val drive = drives().firstOrNull { it.name == segments[0] } ?: return null
        val rel = segments.drop(1).joinToString("/")
        return Node.Item(drive, if (rel.isEmpty()) drive.root else File(drive.root, rel), rel)
    }

    fun child(parent: Node.Item, name: String) =
        Node.Item(parent.drive, File(parent.file, name), if (parent.rel.isEmpty()) name else "${parent.rel}/$name")
}

object FileOps {
    const val TEMP_PREFIX = ".nassaver-upload-"

    fun isHidden(name: String) = name.startsWith(TEMP_PREFIX)

    /** Deletes a file or a folder tree without following symbolic links. */
    fun deleteTree(f: File): Boolean {
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            f.listFiles()?.forEach { if (!deleteTree(it)) return false }
        }
        return f.delete() || !f.exists()
    }

    fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.isDirectory && !dst.mkdirs()) throw IOException("cannot create ${dst.name}")
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else {
            src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it, 256 * 1024) } }
            dst.setLastModified(src.lastModified())
        }
    }

    /** Moves by rename when possible (same drive), otherwise copies and deletes. */
    fun move(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copyTree(src, dst)
        if (!deleteTree(src)) throw IOException("copied but could not remove ${src.name}")
    }

    fun isInside(child: File, parent: File): Boolean {
        val c = child.absoluteFile.normalize().path
        val p = parent.absoluteFile.normalize().path
        return c == p || c.startsWith(p.trimEnd('/') + "/")
    }
}

object Mime {
    private val types = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "ts" to "video/mp2t", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/ogg", "wma" to "audio/x-ms-wma",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "avif" to "image/avif",
        "pdf" to "application/pdf", "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "json" to "application/json", "csv" to "text/csv; charset=utf-8", "vtt" to "text/vtt; charset=utf-8",
        // Text, and anything a browser could run as a page, is served as plain text so it can't act on the server.
        "txt" to "text/plain; charset=utf-8", "srt" to "text/plain; charset=utf-8", "md" to "text/plain; charset=utf-8",
        "log" to "text/plain; charset=utf-8", "ini" to "text/plain; charset=utf-8", "nfo" to "text/plain; charset=utf-8",
        "html" to "text/plain; charset=utf-8", "htm" to "text/plain; charset=utf-8", "xhtml" to "text/plain; charset=utf-8",
        "svg" to "text/plain; charset=utf-8", "xml" to "text/plain; charset=utf-8", "js" to "text/plain; charset=utf-8",
    )

    fun of(name: String): String = types[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"
}
