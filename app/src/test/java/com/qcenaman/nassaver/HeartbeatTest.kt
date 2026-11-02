package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.Clock
import com.qcenaman.nassaver.core.Fmt
import com.qcenaman.nassaver.core.Heartbeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private val min = 60_000L

    @Test fun onTimeTickIsNotLate() {
        val g = Heartbeat.gap(Clock(0, 10 * min, 10 * min), Clock(min, 11 * min, 11 * min))
        assertFalse(g.late)
        assertFalse(g.rebooted)
        assertEquals("on time", Heartbeat.describe(g))
    }

    @Test fun longGapWithSuspendedCpuIsLateAndShowsSleep() {
        val prev = Clock(0, 10 * min, 10 * min)
        val now = Clock(47 * min, 57 * min, 11 * min) // 46 minutes of the 47 the CPU was suspended
        val g = Heartbeat.gap(prev, now)
        assertTrue(g.late)
        assertEquals(47 * min, g.sinceMs)
        assertEquals(46 * min, g.asleepMs)
        assertEquals("LATE: 47m since last tick (CPU asleep 46m)", Heartbeat.describe(g))
    }

    @Test fun smallerBootClockMeansReboot() {
        val g = Heartbeat.gap(Clock(0, 500 * min, 400 * min), Clock(180 * min, 2 * min, 2 * min))
        assertTrue(g.rebooted)
        assertEquals(180 * min, g.sinceMs)
        assertEquals("TV REBOOTED, 3h 00m since last tick", Heartbeat.describe(g))
    }

    @Test fun clockSerializesAndRejectsGarbage() {
        val c = Clock(1793630591000, 123456, 120000)
        assertEquals(c, Clock.parse(c.serialize()))
        assertNull(Clock.parse("1 2"))
        assertNull(Clock.parse("a b c"))
        assertNull(Clock.parse(null))
    }

    @Test fun durationsAreShortAndReadable() {
        assertEquals("45s", Fmt.duration(45_000))
        assertEquals("12m", Fmt.duration(12 * min))
        assertEquals("1h 05m", Fmt.duration(65 * min))
        assertEquals("2d 03h", Fmt.duration((2 * 24 + 3) * 60 * min))
        assertEquals("0s", Fmt.duration(-5))
    }
}
