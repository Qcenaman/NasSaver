package com.qcenaman.nassaver.core

/** One reading of Android's three clocks: wall time, time since boot (incl. sleep), CPU-awake time since boot. */
data class Clock(val wallMs: Long, val elapsedMs: Long, val uptimeMs: Long) {
    /** How long the CPU has been suspended since boot. */
    val asleepMs: Long get() = elapsedMs - uptimeMs

    fun serialize() = "$wallMs $elapsedMs $uptimeMs"

    companion object {
        fun parse(s: String?): Clock? {
            val parts = s?.trim()?.split(' ') ?: return null
            if (parts.size != 3) return null
            val n = parts.map { it.toLongOrNull() ?: return null }
            return Clock(n[0], n[1], n[2])
        }
    }
}

data class Gap(val sinceMs: Long, val asleepMs: Long, val late: Boolean, val rebooted: Boolean)

object Heartbeat {
    const val INTERVAL_MS = 60_000L

    /** Compares two ticks. A tick is late when more than 1.5 intervals passed; a smaller boot clock means a reboot. */
    fun gap(prev: Clock, now: Clock, intervalMs: Long = INTERVAL_MS): Gap {
        val rebooted = now.elapsedMs < prev.elapsedMs
        val since = (if (rebooted) now.wallMs - prev.wallMs else now.elapsedMs - prev.elapsedMs).coerceAtLeast(0)
        val asleep = if (rebooted) 0 else (now.asleepMs - prev.asleepMs).coerceAtLeast(0)
        return Gap(since, asleep, rebooted || since > intervalMs * 3 / 2, rebooted)
    }

    fun describe(gap: Gap): String = when {
        gap.rebooted -> "TV REBOOTED, ${Fmt.duration(gap.sinceMs)} since last tick"
        gap.late -> "LATE: ${Fmt.duration(gap.sinceMs)} since last tick" +
            if (gap.asleepMs >= 1000) " (CPU asleep ${Fmt.duration(gap.asleepMs)})" else ""
        else -> "on time"
    }
}
