package com.freedomfighter.swissbikehike

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/** The resumable download against a small HTTP server that knows Range, If-Range and If-None-Match; the update policy. */
class OfflineTest {
    private lateinit var server: ServerSocket
    @Volatile private var body = Random(1).nextBytes(1_000_000)
    @Volatile private var etag = "\"v1\""
    private val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var dir: File
    private val url get() = "http://127.0.0.1:${server.localPort}/f"

    /** One request per connection; enough HTTP for Range, If-Range, If-None-Match. */
    private fun serve(sock: Socket) = sock.use { s ->
        val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        inp.readLine() ?: return
        val h = mutableMapOf<String, String>()
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) h[l.substring(0, i).lowercase()] = l.substring(i + 1).trim() }
        val range = h["range"]; ranges += range
        val out = s.getOutputStream()
        fun head(code: String, len: Long?, extra: String = "") =
            out.write(("HTTP/1.1 $code\r\nETag: $etag\r\nConnection: close\r\n" + (len?.let { "Content-Length: $it\r\n" } ?: "") + extra + "\r\n").toByteArray())
        val b = body
        try {
            when {
                h["if-none-match"] == etag -> head("304 Not Modified", null)
                range != null && h["if-range"] == etag -> {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    head("206 Partial Content", (b.size - from).toLong(), "Content-Range: bytes $from-${b.size - 1}/${b.size}\r\n")
                    out.write(b, from, b.size - from)
                }
                else -> { head("200 OK", b.size.toLong()); out.write(b) }
            }
            out.flush()
        } catch (_: java.io.IOException) { } // the client hung up (a stopped download)
    }

    @Before fun up() {
        dir = kotlin.io.path.createTempDirectory("offline").toFile()
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { while (!server.isClosed) { val c = runCatching { server.accept() }.getOrNull() ?: break; thread(isDaemon = true) { serve(c) } } }
    }

    @After fun down() { server.close(); dir.deleteRecursively() }

    @Test fun resumesWhereItStopped() = runBlocking {
        val part = File(dir, "f.part")
        var seen = 0L
        val first = Offline.fetch(url, part, null, 0, { d, _ -> seen = d }, { part.length() > 300_000 })
        assertTrue(first is Offline.Fetch.Failed)
        val kept = part.length()
        assertTrue("partial kept: $kept", kept in 300_000 until 1_000_000)
        val second = Offline.fetch(url, part, null, 0, { d, _ -> seen = d }, { false })
        assertTrue(second is Offline.Fetch.Done)
        assertEquals("bytes=$kept-", ranges.last())
        assertArrayEquals(body, part.readBytes())
        assertEquals(1_000_000L, seen)
        assertFalse(File(part.path + ".tag").exists())
    }

    @Test fun startsOverWhenTheServerFileChanged() = runBlocking {
        val part = File(dir, "f.part")
        Offline.fetch(url, part, null, 0, { _, _ -> }, { part.length() > 200_000 })
        body = Random(2).nextBytes(700_000); etag = "\"v2\""
        val r = Offline.fetch(url, part, null, 0, { _, _ -> }, { false })
        assertTrue(r is Offline.Fetch.Done)
        assertArrayEquals(body, part.readBytes())
    }

    @Test fun nothingFetchedWhenUnchanged() = runBlocking {
        val r = Offline.fetch(url, File(dir, "g.part"), "\"v1\"", 0, { _, _ -> }, { false })
        assertTrue(r is Offline.Fetch.NotModified)
        assertFalse(File(dir, "g.part").exists())
    }

    @Test fun policy() {
        val day = 86_400_000L
        val now = 100 * day
        val tt = Offline.Part.TIMETABLE
        assertTrue(Policy.due(tt, now - 7 * day, now, true))
        assertFalse(Policy.due(tt, now - 3 * day, now, true))
        assertFalse(Policy.due(tt, now - 20 * day, now, false))
        assertFalse(Policy.due(Offline.Part.ROUTING, now - 10 * day, now, true))
        assertTrue(Policy.due(Offline.Part.ROUTING, now - 31 * day, now, true))
        assertTrue(Policy.due(Offline.Part.MAP, now - 1 * day, now, true))   // a new swisstopo map: always worth it
        assertFalse(Policy.oldEnough(Offline.Part.MAP, now - 80 * day, now))
        assertTrue(Policy.oldEnough(Offline.Part.MAP, now - 90 * day, now))
        assertFalse(Policy.oldEnough(tt, 0, now))                            // never fetched: not a reminder
    }
}
