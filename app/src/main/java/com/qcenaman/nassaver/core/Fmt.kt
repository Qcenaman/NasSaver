package com.qcenaman.nassaver.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Fmt {
    fun timestamp(wallMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(wallMs))

    /** Short human duration: 45s, 12m, 1h 05m, 2d 03h. */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> String.format(Locale.US, "%dh %02dm", s / 3600, s % 3600 / 60)
            else -> String.format(Locale.US, "%dd %02dh", s / 86400, s % 86400 / 3600)
        }
    }
}
