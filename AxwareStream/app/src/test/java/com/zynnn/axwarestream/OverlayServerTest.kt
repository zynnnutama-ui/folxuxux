package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.overlay.OverlayRepository
import com.zynnn.axwarestream.core.server.OverlayServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Uji server overlay lokal lewat HTTP sungguhan (socket nyata di localhost). */
class OverlayServerTest {
    private lateinit var dir: File
    private lateinit var hub: EventHub
    private lateinit var repo: OverlayRepository
    private lateinit var server: OverlayServer
    private var port = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("axw").toFile()
        hub = EventHub()
        repo = OverlayRepository(File(dir, "p.json"), File(dir, "media"))
        server = OverlayServer(hub, repo, webhookToken = "rahasia")
        port = server.start(0)
    }

    @After fun tearDown() { server.stop(); dir.deleteRecursively() }

    private fun get(path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        return c.responseCode to (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode to (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    @Test fun servesOverlayPageAndProject() {
        val (code, html) = get("/")
        assertEquals(200, code); assertTrue(html.contains("<html") || html.contains("<!DOCTYPE"))
        assertTrue("overlay.html harus dimuat dari resources", html.contains("EventSource"))
        val (c2, json) = get("/api/project")
        assertEquals(200, c2)
        assertEquals(repo.project.value.activeSceneId, JSONObject(json).getString("activeSceneId"))
        assertEquals(200, get("/api/state").first)
        assertEquals(404, get("/tidak-ada").first)
    }

    @Test fun webhookRequiresTokenAndEmits() {
        assertEquals(401, post("/api/event", """{"type":"DONATION","user":"Budi","amount":50000}""").first)
        val (code, body) = post("/api/event?token=rahasia", """{"type":"DONATION","user":"Budi","amount":50000,"message":"gas"}""")
        assertEquals(200, code); assertTrue(body.contains("\"ok\":true"))
        assertEquals(50000L, hub.stats.value.donationTotal)
        assertEquals(EventType.DONATION, hub.recent().last().type)
        assertEquals(400, post("/api/event?token=rahasia", "bukan json").first)
    }

    @Test fun sseDeliversLiveEventsAndProjectChanges() {
        val gotLive = CountDownLatch(1); val gotProject = CountDownLatch(1)
        val t = thread(isDaemon = true) {
            runCatching {
                val c = URL("http://127.0.0.1:$port/events").openConnection() as HttpURLConnection
                c.readTimeout = 15000
                c.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (l.startsWith("event: live")) gotLive.countDown()
                        if (l.startsWith("event: project")) gotProject.countDown()
                        if (gotLive.count == 0L && gotProject.count == 0L) break
                    }
                }
            }
        }
        Thread.sleep(700)
        post("/api/event?token=rahasia", """{"type":"GIFT","user":"Rina","amount":3,"message":"Rose"}""")
        repo.updateScene(repo.project.value.activeSceneId) { it.copy(name = "Diubah") }
        assertTrue("SSE event live", gotLive.await(10, TimeUnit.SECONDS))
        assertTrue("SSE event project", gotProject.await(10, TimeUnit.SECONDS))
        t.interrupt()
    }

    @Test fun repositoryPersistsToDisk() {
        repo.updateScene(repo.project.value.activeSceneId) { it.copy(name = "Tersimpan") }
        repo.flush()
        val again = OverlayRepository(File(dir, "p.json"), File(dir, "media"))
        assertEquals("Tersimpan", again.project.value.activeScene.name)
    }
}
