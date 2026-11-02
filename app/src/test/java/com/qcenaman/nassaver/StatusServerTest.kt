package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.Http
import com.qcenaman.nassaver.core.StatusPage
import com.qcenaman.nassaver.core.StatusServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

class StatusServerTest {
    private val server = StatusServer(0, object : StatusServer.Pages {
        override fun html() = StatusPage.render("t", listOf("Ticks" to "3"), listOf("<script>LATE</script>"))
        override fun log() = "line 1\nline 2\n"
    })

    @Before fun start() = assertTrue(server.start())
    @After fun stop() = server.stop()

    private fun request(raw: String): String = Socket("127.0.0.1", server.boundPort).use {
        it.getOutputStream().write(raw.toByteArray())
        it.getInputStream().readBytes().decodeToString()
    }

    @Test fun healthAnswersOk() {
        val r = request("GET /health HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertTrue(r.startsWith("HTTP/1.1 200 OK"))
        assertTrue(r.endsWith("\r\n\r\nok\n"))
    }

    @Test fun pageEscapesLogLinesAndLogIsRaw() {
        val page = request("GET / HTTP/1.1\r\n\r\n")
        assertTrue(page.contains("&lt;script&gt;LATE&lt;/script&gt;"))
        assertFalse(page.contains("<script>"))
        assertTrue(page.contains("class=\"bad\""))
        assertTrue(request("GET /log.txt?x=1 HTTP/1.0\r\n\r\n").endsWith("line 1\nline 2\n"))
    }

    @Test fun unknownPathsMethodsAndGarbageAreRejected() {
        assertTrue(request("GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(request("PUT / HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 405"))
        assertTrue(request("hello\r\n\r\n").startsWith("HTTP/1.1 400"))
        val head = request("HEAD /health HTTP/1.1\r\n\r\n")
        assertTrue(head.startsWith("HTTP/1.1 200") && head.endsWith("\r\n\r\n"))
    }

    @Test fun requestLineParsing() {
        assertEquals(Http.Request("GET", "/log.txt"), Http.parseRequestLine("GET /log.txt?a=b HTTP/1.1"))
        assertNull(Http.parseRequestLine("GET relative HTTP/1.1"))
        assertNull(Http.parseRequestLine("GET /"))
        assertNull(Http.parseRequestLine(null))
    }
}
