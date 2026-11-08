package com.qcenaman.nassaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveNameTest {
    @get:Rule val tmp = TemporaryFolder()
    private val post = mapOf("X-NasSaver" to "1")
    private fun url(drive: String, name: String) =
        "/api/drive-name?drive=${drive.replace(" ", "%20")}&name=${name.replace(" ", "%20")}"

    @Test fun renameShowsUpEverywhereAndCanBeUndone() = TestServer(tmp.root).use { s ->
        assertEquals(200, s.req("POST", url("Drive A", "Movies"), headers = post).status)
        assertTrue(s.req("GET", "/api/list?path=/").text.contains("\"Movies\""))
        assertEquals(200, s.req("GET", "/api/list?path=/Movies").status)
        assertEquals(404, s.req("GET", "/api/list?path=/Drive%20A").status)
        assertEquals(200, s.req("POST", url("Movies", ""), headers = post).status)
        assertEquals(200, s.req("GET", "/api/list?path=/Drive%20A").status)
    }

    @Test fun refusesBadOrTakenNames() = TestServer(tmp.root).use { s ->
        assertEquals(400, s.req("POST", url("Drive A", "Drive B"), headers = post).status)
        assertEquals(400, s.req("POST", url("Drive A", "a%2Fb"), headers = post).status)
        assertEquals(400, s.req("POST", url("Nope", "X"), headers = post).status)
        assertEquals(403, s.req("POST", url("Drive A", "X")).status)
    }
}
