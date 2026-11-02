package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun keepsLinesAcrossInstancesAndReturnsTail() {
        val file = tmp.root.resolve("logs/heartbeat.log")
        LogStore(file).apply { add("one"); add("two") }
        val store = LogStore(file)
        store.add("three")
        assertEquals(3, store.lines().size)
        assertEquals(listOf("two", "three"), store.tail(2).map { it.substringAfter("  ") })
    }

    @Test fun trimsToNewestHalfWhenFull() {
        val store = LogStore(tmp.newFile(), maxBytes = 2_000)
        repeat(200) { store.add("tick #$it") }
        val lines = store.lines()
        assertTrue(store.text().toByteArray().size <= 2_000)
        assertTrue(lines.last().endsWith("tick #199"))
        assertTrue(lines.none { it.endsWith("tick #0") })
    }
}
