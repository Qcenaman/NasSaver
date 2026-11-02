package com.qcenaman.nassaver.core

object StatusPage {
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    fun lineClass(line: String): String = when {
        listOf("LATE", "REBOOTED", "stopped", "failed", "could not", "MISSING", "is ON").any { line.contains(it) } -> "bad"
        listOf("screen", "screensaver", "started", "shutting", "drive", "login").any { line.contains(it) } -> "info"
        else -> ""
    }

    fun render(title: String, summary: List<Pair<String, String>>, newestFirst: List<String>): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<meta http-equiv=\"refresh\" content=\"60\">")
        append("<title>").append(escape(title)).append("</title><style>")
        append(":root{color-scheme:light dark;--bad:#dc2626;--info:#2563eb;--muted:#6b7280}")
        append("body{font:15px/1.45 system-ui,sans-serif;margin:16px;max-width:900px}")
        append("h1{font-size:20px;margin:0 0 12px}table{border-collapse:collapse;margin-bottom:16px}")
        append("td{padding:3px 12px 3px 0;vertical-align:top}td:first-child{color:var(--muted);white-space:nowrap}")
        append(".log{font:12.5px/1.5 ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}")
        append(".bad{color:var(--bad);font-weight:600}.info{color:var(--info)}")
        append("a{color:var(--info)}</style></head><body>")
        append("<h1>").append(escape(title)).append("</h1><table>")
        for ((k, v) in summary) append("<tr><td>").append(escape(k)).append("</td><td>").append(escape(v)).append("</td></tr>")
        append("</table><p><a href=\"/log.txt\">Full log</a> · refreshes every minute · newest first</p><div class=\"log\">")
        for (line in newestFirst) {
            val cls = lineClass(line)
            if (cls.isEmpty()) append("<div>") else append("<div class=\"").append(cls).append("\">")
            append(escape(line)).append("</div>")
        }
        append("</div></body></html>")
    }
}
