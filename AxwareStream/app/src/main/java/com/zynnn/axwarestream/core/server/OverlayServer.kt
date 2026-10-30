package com.zynnn.axwarestream.core.server

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.overlay.OverlayProject
import com.zynnn.axwarestream.core.overlay.OverlayRepository
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Server HTTP lokal ringan di HP. Menghasilkan "Browser Source URL" untuk OBS / TikTok LIVE Studio
 * di jaringan Wi-Fi yang sama, plus endpoint webhook untuk event donasi/eksternal.
 *
 *  GET  /                -> halaman overlay (render scene aktif, real-time)
 *  GET  /overlay?el=ID   -> hanya satu elemen (per-element overlay)
 *  GET  /overlay?scene=ID-> paksa scene tertentu
 *  GET  /api/project     -> JSON proyek overlay
 *  GET  /api/state       -> JSON statistik + event terbaru
 *  GET  /events          -> Server-Sent Events (event live, project, stats)
 *  POST /api/event       -> webhook: kirim event (JSON). Opsional ?token=
 *  GET  /media/NAME      -> file gambar overlay
 */
class OverlayServer(
    private val hub: EventHub,
    private val repo: OverlayRepository,
    private val htmlProvider: () -> String = { defaultHtml() },
    var webhookToken: String = "",
) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "overlay-http").apply { isDaemon = true } }
    private val sseClients = CopyOnWriteArrayList<OutputStream>()
    @Volatile var port: Int = 0
        private set
    val isRunning: Boolean get() = server?.isClosed == false

    private val eventListener: (LiveEvent) -> Unit = { e -> broadcast("live", e.toJson().toString()) }
    private val projectListener: (OverlayProject) -> Unit = { p -> broadcastProject(p.toJson().toString()) }
    private var heartbeat: Thread? = null

    @Synchronized
    fun start(preferredPort: Int = 8787): Int {
        if (isRunning) return port
        val ss = ServerSocket()
        ss.reuseAddress = true
        try { ss.bind(InetSocketAddress(preferredPort)) } catch (_: Exception) { ss.bind(InetSocketAddress(0)) }
        server = ss
        port = ss.localPort
        hub.addListener(eventListener)
        repo.addListener(projectListener)
        pool.execute {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(s) }
            }
        }
        heartbeat = Thread({
            var lastStats = ""
            while (isRunning) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val st = hub.stats.value.toJson().toString()
                if (st != lastStats) { lastStats = st; broadcast("stats", st) }
                broadcastRaw(": ping\n\n")
            }
        }, "overlay-sse-heartbeat").apply { isDaemon = true; start() }
        return port
    }

    @Synchronized
    fun stop() {
        hub.removeListener(eventListener)
        repo.removeListener(projectListener)
        runCatching { server?.close() }
        server = null
        heartbeat?.interrupt()
        sseClients.forEach { runCatching { it.close() } }
        sseClients.clear()
    }

    private fun broadcast(event: String, data: String) = broadcastRaw("event: $event\ndata: $data\n\n")

    // Penulisan socket selalu di thread khusus (bukan main thread) agar tidak memblokir UI
    // dan tidak memicu NetworkOnMainThreadException di Android.
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "overlay-sse").apply { isDaemon = true } }
    private val pendingProject = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun broadcastProject(data: String) {
        // Saat elemen digeser, perubahan bisa puluhan per detik: kirim versi terbaru saja.
        if (pendingProject.getAndSet(data) == null) sender.execute {
            val latest = pendingProject.getAndSet(null) ?: return@execute
            writeAll("event: project\ndata: $latest\n\n")
        }
    }

    private fun broadcastRaw(payload: String) {
        runCatching { sender.execute { writeAll(payload) } }
    }

    private fun writeAll(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        for (c in sseClients) {
            try { synchronized(c) { c.write(bytes); c.flush() } } catch (_: Exception) { sseClients.remove(c); runCatching { c.close() } }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32 * 1024) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { l -> val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(1024 * 1024) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val target = parts[1]
        val path = target.substringBefore("?")
        val query = HashMap<String, String>()
        if (target.contains("?")) target.substringAfter("?").split("&").forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) query[URLDecoder.decode(kv.substring(0, i), "UTF-8")] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }
        return Req(parts[0].uppercase(), path, query, headers, body)
    }

    private fun handle(socket: Socket) {
        var keepOpen = false
        try {
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val req = readRequest(input) ?: return
            when {
                req.method == "OPTIONS" -> respond(out, 204, "text/plain", ByteArray(0))
                req.method == "GET" && (req.path == "/" || req.path == "/overlay" || req.path == "/overlay/") ->
                    respond(out, 200, "text/html; charset=utf-8", htmlProvider().toByteArray())
                req.method == "GET" && req.path == "/api/project" ->
                    respond(out, 200, "application/json; charset=utf-8", repo.project.value.toJson().toString().toByteArray())
                req.method == "GET" && req.path == "/api/state" ->
                    respond(out, 200, "application/json; charset=utf-8", hub.stateJson().toString().toByteArray())
                req.method == "GET" && req.path == "/events" -> {
                    socket.soTimeout = 0
                    val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                        "Connection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
                    out.write(hdr.toByteArray()); out.write("retry: 2000\n\n".toByteArray()); out.flush()
                    synchronized(out) {
                        out.write("event: project\ndata: ${repo.project.value.toJson()}\n\n".toByteArray())
                        out.write("event: stats\ndata: ${hub.stats.value.toJson()}\n\n".toByteArray())
                        out.flush()
                    }
                    sseClients.add(out)
                    keepOpen = true
                }
                req.method == "POST" && req.path == "/api/event" -> {
                    if (webhookToken.isNotBlank() && req.query["token"] != webhookToken && req.headers["x-axware-token"] != webhookToken) {
                        respond(out, 401, "application/json", "{\"ok\":false,\"error\":\"token salah\"}".toByteArray())
                    } else {
                        val ev = try { LiveEvent.fromWebhookJson(JSONObject(String(req.body, Charsets.UTF_8))) } catch (e: Exception) { null }
                        if (ev == null) respond(out, 400, "application/json", "{\"ok\":false,\"error\":\"JSON tidak valid\"}".toByteArray())
                        else { hub.emit(ev); respond(out, 200, "application/json", "{\"ok\":true,\"id\":\"${ev.id}\"}".toByteArray()) }
                    }
                }
                req.method == "GET" && req.path.startsWith("/media/") -> {
                    val f = repo.mediaFile(req.path.removePrefix("/media/"))
                    if (f == null) respond(out, 404, "text/plain", "not found".toByteArray())
                    else respond(out, 200, mime(f.name), f.readBytes())
                }
                else -> respond(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            if (!keepOpen) runCatching { socket.close() }
        }
    }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; else -> "Not Found" }
        val hdr = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type, X-Axware-Token\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray()); out.write(body); out.flush()
    }

    companion object {
        fun defaultHtml(): String =
            OverlayServer::class.java.getResourceAsStream("/overlay/overlay.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: "<html><body>overlay.html tidak ditemukan</body></html>"

        /** Alamat IPv4 LAN (Wi-Fi/hotspot) perangkat ini. */
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .map(InetAddress::getHostAddress)
        }.getOrDefault(emptyList())
    }
}
