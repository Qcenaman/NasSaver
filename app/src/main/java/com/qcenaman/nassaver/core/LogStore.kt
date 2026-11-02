package com.qcenaman.nassaver.core

import java.io.File

/** Append-only text log that survives restarts; trims to the newest half once it passes [maxBytes]. */
class LogStore(
    private val file: File,
    private val maxBytes: Long = 512L * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun add(message: String) {
        file.parentFile?.mkdirs()
        file.appendText("${Fmt.timestamp(now())}  $message\n")
        if (file.length() > maxBytes) trim()
    }

    @Synchronized
    fun lines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotEmpty() } else emptyList()

    @Synchronized
    fun tail(n: Int): List<String> = lines().takeLast(n)

    @Synchronized
    fun text(): String = if (file.exists()) file.readText() else ""

    private fun trim() {
        val kept = ArrayDeque<String>()
        var size = 0L
        for (line in lines().asReversed()) {
            val bytes = line.toByteArray().size + 1
            if (size + bytes > maxBytes / 2) break
            kept.addFirst(line)
            size += bytes
        }
        file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }
}
