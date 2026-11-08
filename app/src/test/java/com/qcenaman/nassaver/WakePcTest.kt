package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.VpnWatch
import com.qcenaman.nassaver.core.Wol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakePcTest {
    @get:Rule val tmp = TemporaryFolder()
    private val post = mapOf("X-NasSaver" to "1")

    @Test fun packetIsSyncThenMacSixteenTimes() {
        val mac = Wol.parseMac("10-ff-E0:3D.EA0A")!!
        assertEquals("10-FF-E0-3D-EA-0A", Wol.format(mac))
        val p = Wol.packet(mac)
        assertEquals(102, p.size)
        assertArrayEquals(ByteArray(6) { -1 }, p.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
        assertNull(Wol.parseMac("10-FF-E0-3D-EA"))
        assertNull(Wol.parseMac("zz-FF-E0-3D-EA-0A"))
    }

    @Test fun apiSavesTheComputerAndWakesIt() = TestServer(tmp.root).use { s ->
        assertEquals(409, s.req("POST", "/api/wol", headers = post).status)
        assertEquals(400, s.req("POST", "/api/wol?mac=nope", headers = post).status)
        assertEquals(200, s.req("POST", "/api/wol?mac=10:FF:E0:3D:EA:0A", headers = post).status)
        assertEquals("{\"mac\":\"10-FF-E0-3D-EA-0A\"}", s.req("GET", "/api/wol").text)
        assertEquals(200, s.req("POST", "/api/wol", headers = post).status)
        assertEquals(2, s.packets.size)
    }

    @Test fun apiNeedsLoginAndThePageHeader() = TestServer(tmp.root).use { s ->
        assertEquals(401, s.req("POST", "/api/wol", headers = post, login = false).status)
        assertEquals(403, s.req("POST", "/api/wol").status)
        assertEquals(0, s.packets.size)
    }

    @Test fun watchReconnectsOnlyWhileDownAndLogsChanges() {
        var up = false
        var asks = 0
        val logged = mutableListOf<String>()
        val w = VpnWatch({ up }, { asks++ }, { logged += it })
        w.tick(); w.tick()
        assertEquals(2, asks)
        up = true
        w.tick(); w.tick()
        assertEquals(2, asks)
        assertEquals(2, logged.size)
    }
}
