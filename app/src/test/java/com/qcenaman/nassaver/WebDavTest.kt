package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.dav.WebDav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDavTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var s: TestServer

    @Before fun start() { s = TestServer(tmp.root) }
    @After fun stop() = s.close()

    @Test fun everythingButHealthNeedsTheLogin() {
        assertEquals(401, s.req("PROPFIND", "/dav/", login = false).status)
        assertEquals(401, s.req("GET", "/", login = false).status)
        assertEquals(401, s.req("GET", "/dav/", headers = mapOf("Authorization" to TestServer.basic("tv", "nope")), login = false).status)
        assertEquals(200, s.req("GET", "/health", login = false).status)
        assertEquals(200, s.req("GET", "/").status)
    }

    @Test fun rootListsDrivesAndFoldersListTheirFiles() {
        File(s.driveA, "Movies").mkdir()
        File(s.driveA, "Movies/film one.mp4").writeText("12345")
        File(s.driveA, "Movies/.nassaver-upload-x").writeText("temp")

        val root = s.req("PROPFIND", "/dav/", headers = mapOf("Depth" to "1"))
        assertEquals(207, root.status)
        assertTrue(root.text.contains("<D:href>/dav/Drive%20A/</D:href>"))
        assertTrue(root.text.contains("<D:href>/dav/Drive%20B/</D:href>"))

        val folder = s.req("PROPFIND", "/dav/Drive%20A/Movies", headers = mapOf("Depth" to "1")).text
        assertTrue(folder.contains("<D:href>/dav/Drive%20A/Movies/film%20one.mp4</D:href>"))
        assertTrue(folder.contains("<D:getcontentlength>5</D:getcontentlength>"))
        assertTrue(folder.contains("<D:getcontenttype>video/mp4</D:getcontenttype>"))
        assertFalse(folder.contains("nassaver-upload"))

        val one = s.req("PROPFIND", "/dav/Drive%20A/Movies", headers = mapOf("Depth" to "0")).text
        assertFalse(one.contains("film"))
        assertEquals(404, s.req("PROPFIND", "/dav/Nope/").status)
        assertEquals(404, s.req("PROPFIND", "/dav/Drive%20A/../b").status)
    }

    @Test fun uploadDownloadAndRanges() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(201, s.req("PUT", "/dav/Drive%20A/data.bin", data).status)
        assertEquals(204, s.req("PUT", "/dav/Drive%20A/data.bin", data).status)
        assertTrue(File(s.driveA, "data.bin").readBytes().contentEquals(data))
        assertEquals(0, s.driveA.listFiles()!!.count { it.name.startsWith(".nassaver") })

        val full = s.req("GET", "/dav/Drive%20A/data.bin")
        assertEquals(200, full.status)
        assertTrue(full.body.contentEquals(data))
        assertEquals("bytes", full.header("accept-ranges"))

        val part = s.req("GET", "/dav/Drive%20A/data.bin", headers = mapOf("Range" to "bytes=1000-1999"))
        assertEquals(206, part.status)
        assertEquals("bytes 1000-1999/300000", part.header("content-range"))
        assertTrue(part.body.contentEquals(data.copyOfRange(1000, 2000)))

        assertEquals(416, s.req("GET", "/dav/Drive%20A/data.bin", headers = mapOf("Range" to "bytes=400000-")).status)
        val dl = s.req("GET", "/dav/Drive%20A/data.bin?download=1")
        assertTrue(dl.header("content-disposition")!!.contains("data.bin"))
        assertEquals(409, s.req("PUT", "/dav/Drive%20A/missing/x.txt", "x".toByteArray()).status)
    }

    @Test fun foldersRenameMoveCopyDelete() {
        assertEquals(201, s.req("MKCOL", "/dav/Drive%20A/New").status)
        assertEquals(405, s.req("MKCOL", "/dav/Drive%20A/New").status)
        s.req("PUT", "/dav/Drive%20A/New/a.txt", "hello".toByteArray())
        s.req("PUT", "/dav/Drive%20A/b.txt", "other".toByteArray())

        val dest = { p: String -> mapOf("Destination" to "http://tv:8080/dav/$p", "Overwrite" to "F") }
        assertEquals(409, s.req("MOVE", "/dav/Drive%20A/b.txt", headers = dest("Drive%20A/New/../b.txt")).status)
        assertEquals(201, s.req("MOVE", "/dav/Drive%20A/b.txt", headers = dest("Drive%20A/New/c.txt")).status)
        assertEquals(412, s.req("MOVE", "/dav/Drive%20A/New/c.txt", headers = dest("Drive%20A/New/a.txt")).status)
        assertEquals(403, s.req("MOVE", "/dav/Drive%20A/New", headers = dest("Drive%20A/New/inside")).status)

        assertEquals(201, s.req("COPY", "/dav/Drive%20A/New", headers = dest("Drive%20B/Copied")).status)
        assertEquals("hello", File(s.driveB, "Copied/a.txt").readText())
        assertEquals(201, s.req("MOVE", "/dav/Drive%20A/New", headers = dest("Drive%20B/Moved")).status)
        assertFalse(File(s.driveA, "New").exists())
        assertEquals("other", File(s.driveB, "Moved/c.txt").readText())

        assertEquals(204, s.req("DELETE", "/dav/Drive%20B/Moved").status)
        assertFalse(File(s.driveB, "Moved").exists())
        assertEquals(403, s.req("DELETE", "/dav/Drive%20B").status)
        assertTrue(s.logLines.any { it.startsWith("delete /Drive B/Moved") })
    }

    @Test fun locksAndProppatchKeepWindowsAndMacHappy() {
        val opt = s.req("OPTIONS", "/dav/")
        assertEquals("1, 2", opt.header("dav"))
        val lock = s.req("LOCK", "/dav/Drive%20A/new.docx", "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"/>".toByteArray())
        assertEquals(201, lock.status)
        assertTrue(lock.header("lock-token")!!.startsWith("<opaquelocktoken:"))
        assertTrue(File(s.driveA, "new.docx").exists())
        assertEquals(204, s.req("UNLOCK", "/dav/Drive%20A/new.docx").status)
        val patch = s.req(
            "PROPPATCH", "/dav/Drive%20A/new.docx",
            ("<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\">" +
                "<D:set><D:prop><Z:Win32LastModifiedTime>x</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>").toByteArray(),
        )
        assertEquals(207, patch.status)
        assertTrue(patch.text.contains("Win32LastModifiedTime"))
    }

    @Test fun apiListsAndSearches() {
        File(s.driveA, "Holiday 2024").mkdir()
        File(s.driveA, "Holiday 2024/beach photo.jpg").writeText("x")
        File(s.driveB, "notes.txt").writeText("x")
        val root = s.req("GET", "/api/list?path=%2F").text
        assertTrue(root.contains("\"name\":\"Drive A\",\"dir\":true,\"drive\":true"))
        val folder = s.req("GET", "/api/list?path=%2FDrive+A%2FHoliday+2024").text
        assertTrue(folder.contains("\"name\":\"beach photo.jpg\",\"dir\":false,\"size\":1"))
        val found = s.req("GET", "/api/search?path=%2F&q=BEACH+jpg").text
        assertTrue(found.contains("\"path\":\"/Drive A/Holiday 2024/beach photo.jpg\""))
        assertFalse(found.contains("notes"))
    }

    @Test fun rangeParsing() {
        assertEquals(0L to 99L, WebDav.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, WebDav.parseRange("bytes=900-", 1000))
        assertEquals(900L to 999L, WebDav.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, WebDav.parseRange("bytes=-5000", 1000))
        assertEquals(500L to 999L, WebDav.parseRange("bytes=500-5000", 1000))
        assertEquals(WebDav.RANGE_INVALID, WebDav.parseRange("bytes=1000-", 1000))
        assertNull(WebDav.parseRange("bytes=0-1,5-6", 1000))
        assertNull(WebDav.parseRange("items=0-1", 1000))
    }
}
