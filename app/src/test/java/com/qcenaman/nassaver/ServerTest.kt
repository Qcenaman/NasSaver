package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.auth.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

class ServerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun keepAliveCarriesSeveralRequests() = TestServer(tmp.root).use { s ->
        Socket("127.0.0.1", s.port).use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream().buffered()
            val auth = TestServer.basic(TestServer.USER, TestServer.PASS)
            repeat(3) {
                out.write("GET /health HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
                assertEquals("ok\n", TestServer.readOne(input).text)
            }
            out.write("PUT /dav/Drive%20A/k.txt HTTP/1.1\r\nHost: tv\r\nAuthorization: $auth\r\nContent-Length: 2\r\n\r\nhi".toByteArray())
            assertEquals(201, TestServer.readOne(input).status)
            out.write("GET /dav/Drive%20A/k.txt HTTP/1.1\r\nHost: tv\r\nAuthorization: $auth\r\n\r\n".toByteArray())
            assertEquals("hi", TestServer.readOne(input).text)
        }
    }

    @Test fun chunkedUploadWithExpectContinue() = TestServer(tmp.root).use { s ->
        Socket("127.0.0.1", s.port).use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream().buffered()
            out.write(
                ("PUT /dav/Drive%20A/chunked.txt HTTP/1.1\r\nHost: tv\r\nAuthorization: ${TestServer.basic(TestServer.USER, TestServer.PASS)}\r\n" +
                    "Transfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n").toByteArray()
            )
            val interim = StringBuilder()
            while (!interim.endsWith("\r\n\r\n")) interim.append(input.read().toChar())
            assertTrue(interim.startsWith("HTTP/1.1 100 Continue"))
            out.write("5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\n\r\n".toByteArray())
            assertEquals(201, TestServer.readOne(input).status)
        }
        assertEquals("hello, world", File(s.driveA, "chunked.txt").readText())
    }

    @Test fun rejectedUploadDoesNotWaitForTheBody() = TestServer(tmp.root).use { s ->
        Socket("127.0.0.1", s.port).use { sock ->
            sock.soTimeout = 5000
            sock.getOutputStream().write(
                "PUT /dav/Drive%20A/x.bin HTTP/1.1\r\nHost: tv\r\nContent-Length: 999999999\r\nExpect: 100-continue\r\n\r\n".toByteArray()
            )
            val r = TestServer.parse(sock.getInputStream().readBytes())
            assertEquals(401, r.status)
            assertEquals("close", r.header("connection"))
        }
        assertFalse(File(s.driveA, "x.bin").exists())
    }

    @Test fun setupPageCreatesTheLoginOnce() = TestServer(tmp.root, withLogin = false).use { s ->
        assertEquals(503, s.req("PROPFIND", "/dav/", login = false).status)
        assertEquals("/setup", s.req("GET", "/", login = false).header("location"))
        val mismatch = s.req("POST", "/setup", "user=tv&password=secret1&confirm=other".toByteArray(), login = false)
        assertEquals(400, mismatch.status)
        assertTrue(mismatch.text.contains("don&#39;t match") || mismatch.text.contains("don't match"))
        assertEquals(400, s.req("POST", "/setup", "user=tv&password=abc&confirm=abc".toByteArray(), login = false).status)
        assertEquals(200, s.req("POST", "/setup", "user=tv&password=secret1&confirm=secret1".toByteArray(), login = false).status)
        assertEquals(401, s.req("POST", "/setup", "user=evil&password=secret2&confirm=secret2".toByteArray(), login = false).status)
        assertEquals("tv", s.auth.user)
        assertEquals(207, s.req("PROPFIND", "/dav/").status)
    }

    @Test fun wrongPasswordsLockOutOnlyThatAddress() {
        var now = 0L
        val store = object : Auth.Store {
            var saved: Auth.Login? = null
            override fun load() = saved
            override fun save(login: Auth.Login?) { saved = login }
        }
        val auth = Auth(store, now = { now }, iterations = 1000)
        assertEquals(null, auth.setLogin("tv", "secret1"))
        val bad = TestServer.basic("tv", "wrong")
        val good = TestServer.basic("tv", "secret1")
        repeat(Auth.MAX_FAILURES - 1) { assertEquals(Auth.Result.BAD, auth.check(bad, "10.0.0.5")) }
        assertEquals(Auth.Result.LOCKED, auth.check(bad, "10.0.0.5"))
        assertEquals(Auth.Result.LOCKED, auth.check(good, "10.0.0.5"))
        assertEquals(Auth.Result.OK, auth.check(good, "10.0.0.6"))
        now += Auth.LOCK_MS + 1
        assertEquals(Auth.Result.OK, auth.check(good, "10.0.0.5"))
        assertEquals(Auth.Result.MISSING, auth.check(null, "10.0.0.5"))
        assertTrue(auth.setLogin("a:b", "secret1")!!.contains("colon"))
        auth.reset()
        assertFalse(auth.isConfigured)
        assertEquals(null, store.saved)
    }
}
