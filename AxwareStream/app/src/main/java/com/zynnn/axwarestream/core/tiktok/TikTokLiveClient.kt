package com.zynnn.axwarestream.core.tiktok

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class TikTokState { IDLE, CONNECTING, CONNECTED, WAITING_LIVE, RECONNECTING, ERROR }

data class TikTokStatus(
    val state: TikTokState = TikTokState.IDLE,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val title: String = "",
    val roomId: String = "",
    val detail: String = "",
    val connectedAt: Long = 0,
)

/** Info room hasil pencarian @username. */
data class RoomInfo(val roomId: String, val live: Boolean, val nickname: String, val avatar: String, val title: String, val viewers: Long)

class TikTokException(val kind: Kind, msg: String) : Exception(msg) {
    enum class Kind { NOT_FOUND, OFFLINE, SIGN, NETWORK, BLOCKED }
}

/**
 * Klien event TikTok LIVE (komentar, gift, like, follow, share, penonton masuk, jumlah penonton).
 *
 * Alur (sama dengan library open-source TikTokLive/TikTok-Live-Connector):
 * 1. Cari room live dari @username lewat endpoint web TikTok `api-live/user/room`.
 * 2. Minta URL WebSocket bertanda tangan (X-Bogus, dll) ke sign server Euler Stream `/webcast/fetch`.
 *    TikTok mewajibkan tanda tangan ini; tanpa API key berlaku batas pemakaian gratis Euler Stream.
 * 3. Buka WebSocket ke push server TikTok, kirim im_enter_room, heartbeat tiap 10 detik, ack bila diminta.
 * 4. Dekode pesan protobuf menjadi [LiveEvent] di [EventHub].
 */
class TikTokLiveClient(
    private val hub: EventHub,
    private val http: OkHttpClient = defaultHttp,
    private val signApiKey: () -> String = { "" },
    private val signBase: String = "https://api.eulerstream.com",
) {
    private val _status = MutableStateFlow(TikTokStatus())
    val status: StateFlow<TikTokStatus> = _status.asStateFlow()

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tiktok-live").apply { isDaemon = true } }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    private val generation = AtomicLong(0)
    private var heartbeat: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var attempts = 0
    private val hbSeq = AtomicLong(1)
    @Volatile var messagesReceived = 0L; private set
    /** Penyadap pesan mentah (method, payload) untuk diagnosa/riset protokol. Null = tidak aktif. */
    @Volatile var rawTap: ((String, ByteArray) -> Unit)? = null

    fun connect(input: String) {
        val user = TikTokProtocol.normalizeUsername(input)
        if (user.isBlank()) { _status.value = TikTokStatus(TikTokState.ERROR, detail = "Username TikTok belum diisi"); return }
        exec.execute {
            stopInternal()
            wanted = true; attempts = 0
            _status.value = TikTokStatus(TikTokState.CONNECTING, username = user, detail = "Mencari live @$user…")
            open(user, generation.incrementAndGet())
        }
    }

    fun disconnect() {
        exec.execute {
            wanted = false
            generation.incrementAndGet()
            stopInternal()
            hub.clearViewers(Platform.TIKTOK)
            _status.value = TikTokStatus(TikTokState.IDLE, username = _status.value.username)
        }
    }

    private fun stopInternal() {
        heartbeat?.cancel(false); heartbeat = null
        retry?.cancel(false); retry = null
        ws?.close(1000, "bye"); ws = null
    }

    private fun schedule(user: String, gen: Long, delaySec: Long, state: TikTokState, detail: String) {
        if (!wanted || gen != generation.get()) return
        _status.value = _status.value.copy(state = state, detail = detail)
        retry = exec.schedule({ if (wanted && gen == generation.get()) open(user, gen) }, delaySec, TimeUnit.SECONDS)
    }

    private fun open(user: String, gen: Long) {
        if (!wanted || gen != generation.get()) return
        try {
            val room = lookupRoom(user)
            _status.value = _status.value.copy(nickname = room.nickname, avatar = room.avatar, title = room.title, roomId = room.roomId)
            if (!room.live) throw TikTokException(TikTokException.Kind.OFFLINE, "@$user sedang tidak live")
            if (room.viewers > 0) hub.setViewers(Platform.TIKTOK, room.viewers)
            val signed = fetchSigned(room.roomId)
            dispatchAll(signed.result.messages, initial = true)
            val url = buildWsUrl(signed.result, room.roomId)
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cookie", listOfNotNull(signed.cookie.takeIf { it.isNotBlank() }, "tt-target-idc=useast1a").joinToString("; "))
                .header("Origin", "https://www.tiktok.com")
                .header("Sec-WebSocket-Protocol", "echo-protocol")
                .build()
            val roomId = room.roomId.toLong()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    exec.execute {
                        if (gen != generation.get()) { webSocket.close(1000, null); return@execute }
                        attempts = 0
                        webSocket.send(TikTokProtocol.enterRoom(roomId).toByteString())
                        heartbeat?.cancel(false)
                        heartbeat = exec.scheduleAtFixedRate({
                            ws?.send(TikTokProtocol.heartbeat(roomId, hbSeq.getAndIncrement()).toByteString())
                        }, 0, 10, TimeUnit.SECONDS)
                        _status.value = _status.value.copy(state = TikTokState.CONNECTED, detail = "Terhubung ke live @$user",
                            connectedAt = System.currentTimeMillis())
                        hub.emit(LiveEvent(Platform.SYSTEM, EventType.SYSTEM, "TikTok", "Terhubung ke live @$user"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (gen != generation.get()) return
                    runCatching { onFrame(webSocket, bytes.toByteArray(), user, gen) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    exec.execute {
                        if (gen != generation.get() || !wanted) return@execute
                        heartbeat?.cancel(false); ws = null
                        val code = response?.code
                        attempts++
                        schedule(user, gen, backoff(), TikTokState.RECONNECTING,
                            "Koneksi terputus${if (code != null) " ($code)" else ""}: ${t.message ?: t.javaClass.simpleName}. Menyambung ulang…")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    exec.execute {
                        if (gen != generation.get() || !wanted) return@execute
                        heartbeat?.cancel(false); ws = null
                        attempts++
                        schedule(user, gen, backoff(), TikTokState.RECONNECTING, "Koneksi ditutup TikTok ($code). Menyambung ulang…")
                    }
                }
            })
        } catch (e: TikTokException) {
            when (e.kind) {
                TikTokException.Kind.OFFLINE -> schedule(user, gen, 20, TikTokState.WAITING_LIVE,
                    "@$user belum live. Otomatis tersambung saat live dimulai.")
                TikTokException.Kind.NOT_FOUND -> { wanted = false; _status.value = _status.value.copy(state = TikTokState.ERROR, detail = e.message ?: "") }
                else -> { attempts++; schedule(user, gen, backoff(), TikTokState.RECONNECTING, (e.message ?: "Gagal") + " Mencoba lagi…") }
            }
        } catch (e: Exception) {
            attempts++
            schedule(user, gen, backoff(), TikTokState.RECONNECTING, "Gagal terhubung: ${e.message ?: e.javaClass.simpleName}. Mencoba lagi…")
        }
    }

    private fun backoff(): Long = minOf(60L, 3L * (1L shl minOf(attempts, 4)))

    private fun onFrame(webSocket: WebSocket, data: ByteArray, user: String, gen: Long) {
        val f = TikTokProtocol.parseFrame(data)
        if (f.payloadType != "msg") return
        val res = TikTokProtocol.parseFetchResult(TikTokProtocol.framePayload(f))
        if (res.needAck) webSocket.send(TikTokProtocol.ack(f.logId, res.internalExt).toByteString())
        dispatchAll(res.messages, initial = false)?.let { action ->
            if (action == TikTokProtocol.CONTROL_STREAM_ENDED || action == TikTokProtocol.CONTROL_STREAM_SUSPENDED) {
                exec.execute {
                    if (gen != generation.get()) return@execute
                    hub.emit(LiveEvent(Platform.SYSTEM, EventType.SYSTEM, "TikTok", "Live @$user telah berakhir"))
                    heartbeat?.cancel(false); ws?.close(1000, "ended"); ws = null
                    hub.clearViewers(Platform.TIKTOK)
                    generation.incrementAndGet().let { g -> schedule(user, g, 20, TikTokState.WAITING_LIVE, "Live berakhir. Menunggu live berikutnya…") }
                }
            }
        }
    }

    /** Kirim semua pesan ke hub. Mengembalikan kode Control bila ada. */
    private fun dispatchAll(msgs: List<Pair<String, ByteArray>>, initial: Boolean): Int? {
        var control: Int? = null
        for ((method, payload) in msgs) {
            messagesReceived++
            rawTap?.let { tap -> runCatching { tap(method, payload) } }
            when (val d = TikTokProtocol.decode(method, payload)) {
                is TikTokProtocol.Decoded.Event -> hub.emit(d.event)
                is TikTokProtocol.Decoded.Like -> { hub.emit(d.event); if (d.total > 0) hub.setLikeTotal(d.total) }
                is TikTokProtocol.Decoded.Viewers -> hub.setViewers(Platform.TIKTOK, d.count)
                is TikTokProtocol.Decoded.Control -> if (!initial) control = d.action
                null -> {}
            }
        }
        return control
    }

    fun lookupRoom(user: String): RoomInfo {
        val url = "https://www.tiktok.com/api-live/user/room/".toHttpUrl().newBuilder()
            .addQueryParameter("aid", "1988").addQueryParameter("app_name", "tiktok_web")
            .addQueryParameter("device_platform", "web_pc").addQueryParameter("app_language", "en")
            .addQueryParameter("browser_language", "en-US").addQueryParameter("uniqueId", user)
            .addQueryParameter("sourceType", "54").build()
        val body = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://www.tiktok.com/").build()).execute().use { r ->
                if (!r.isSuccessful) throw TikTokException(TikTokException.Kind.NETWORK, "TikTok menolak permintaan (${r.code}).")
                r.body?.string().orEmpty()
            }
        } catch (e: TikTokException) { throw e } catch (e: Exception) {
            throw TikTokException(TikTokException.Kind.NETWORK, "Tidak bisa menghubungi TikTok: ${e.message}")
        }
        if (body.isBlank()) throw TikTokException(TikTokException.Kind.BLOCKED, "TikTok mengembalikan respons kosong (kemungkinan dibatasi).")
        val o = runCatching { JSONObject(body) }.getOrElse { throw TikTokException(TikTokException.Kind.BLOCKED, "Respons TikTok tidak valid.") }
        val data = o.optJSONObject("data")
        val u = data?.optJSONObject("user")
        if (o.optInt("statusCode", 0) != 0 || u == null)
            throw TikTokException(TikTokException.Kind.NOT_FOUND, "Akun @$user tidak ditemukan atau belum pernah live.")
        val roomId = u.optString("roomId")
        val lr = data.optJSONObject("liveRoom")
        val live = roomId.isNotBlank() && roomId != "0" && (lr?.optInt("status") == 2 || u.optInt("status") == 2)
        return RoomInfo(roomId, live, u.optString("nickname"), u.optString("avatarThumb"), lr?.optString("title").orEmpty(),
            lr?.optJSONObject("liveRoomStats")?.optLong("userCount") ?: 0)
    }

    private data class Signed(val result: TikTokProtocol.FetchResult, val cookie: String)

    private fun fetchSigned(roomId: String): Signed {
        val url = "$signBase/webcast/fetch".toHttpUrl().newBuilder()
            .addQueryParameter("client", "axware-stream").addQueryParameter("room_id", roomId)
            .addQueryParameter("user_agent", UA).addQueryParameter("client_enter", "true")
            .addQueryParameter("platform", "web").build()
        val rb = Request.Builder().url(url).header("User-Agent", "AxwareStream/1.0")
        signApiKey().trim().takeIf { it.isNotEmpty() }?.let { rb.header("X-Api-Key", it) }
        return try {
            http.newCall(rb.build()).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(String(bytes)).optString("message") }.getOrNull().orEmpty()
                    throw TikTokException(TikTokException.Kind.SIGN, when (r.code) {
                        429 -> "Batas gratis sign server Euler Stream tercapai. Tunggu sebentar atau isi API key di Setelan."
                        401, 403 -> "Sign server menolak (${r.code}). Periksa API key Euler Stream di Setelan."
                        else -> "Sign server error ${r.code}${if (msg.isNotBlank()) ": $msg" else ""}."
                    })
                }
                val res = TikTokProtocol.parseFetchResult(bytes)
                if (res.pushServer.isBlank()) throw TikTokException(TikTokException.Kind.SIGN, "Sign server tidak mengembalikan alamat WebSocket.")
                Signed(res, r.header("x-set-tt-cookie").orEmpty().substringBefore(';'))
            }
        } catch (e: TikTokException) { throw e } catch (e: Exception) {
            throw TikTokException(TikTokException.Kind.NETWORK, "Tidak bisa menghubungi sign server: ${e.message}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun buildWsUrl(res: TikTokProtocol.FetchResult, roomId: String): String {
        val p = LinkedHashMap<String, String>()
        res.routeParams.forEach { (k, v) -> if (v.isNotEmpty()) p[k] = enc(v) }
        val base = mapOf(
            "aid" to "1988", "app_language" to "en", "app_name" to "tiktok_web", "browser_platform" to "Win32",
            "browser_language" to "en-US", "browser_name" to "Mozilla", "browser_version" to enc(UA.removePrefix("Mozilla/")),
            "browser_online" to "true", "cookie_enabled" to "true", "tz_name" to enc(TimeZone.getDefault().id),
            "device_platform" to "web", "identity" to "audience", "live_id" to "12", "sup_ws_ds_opt" to "1",
            "update_version_code" to "2.0.0", "version_code" to "180800", "client_enter" to "1", "ws_direct" to "1",
            "did_rule" to "3", "webcast_language" to "en", "screen_height" to "1080", "screen_width" to "1920",
            "heartbeat_duration" to "10000", "resp_content_type" to "protobuf", "history_comment_count" to "6",
            "last_rtt" to "150", "room_id" to roomId, "compress" to "gzip",
        )
        base.forEach { (k, v) -> if (!p.containsKey(k)) p[k] = v }
        val ws = res.pushServer.replaceFirst("https://", "wss://")
        return ws + (if ('?' in ws) "&" else "?") + p.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS).build()
        }
    }
}
