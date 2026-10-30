package com.zynnn.axwarestream.core.tiktok

import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Definisi pesan TikTok LIVE Webcast (nomor field mengikuti skema proto yang dipakai library open-source
 * TikTokLive / TikTok-Live-Connector) dan konversinya ke [LiveEvent] aplikasi.
 */
object TikTokProtocol {

    data class PushFrame(val logId: Long, val payloadType: String, val payload: ByteArray, val headers: Map<String, String>)

    data class FetchResult(
        val messages: List<Pair<String, ByteArray>>,
        val cursor: String,
        val internalExt: String,
        val routeParams: Map<String, String>,
        val needAck: Boolean,
        val pushServer: String,
    )

    /** Hasil dekode satu pesan: event untuk hub, dan/atau pembaruan status room. */
    sealed class Decoded {
        data class Event(val event: LiveEvent) : Decoded()
        data class Viewers(val count: Long) : Decoded()
        /** Like: event per pengirim + total like room (dari server TikTok). */
        data class Like(val event: LiveEvent, val total: Long) : Decoded()
        data class Control(val action: Int) : Decoded()
    }

    fun parseFrame(b: ByteArray): PushFrame {
        val m = ProtoMsg.parse(b)
        return PushFrame(m.long(2), m.string(7), m.bytes(8) ?: ByteArray(0), m.stringMap(5))
    }

    fun framePayload(f: PushFrame): ByteArray =
        if (f.headers["compress_type"] == "gzip" || (f.payload.size > 2 && f.payload[0] == 0x1f.toByte() && f.payload[1] == 0x8b.toByte()))
            GZIPInputStream(ByteArrayInputStream(f.payload)).use { it.readBytes() } else f.payload

    fun parseFetchResult(b: ByteArray): FetchResult {
        val m = ProtoMsg.parse(b)
        return FetchResult(
            messages = m.msgs(1).map { it.string(1) to (it.bytes(2) ?: ByteArray(0)) },
            cursor = m.string(2), internalExt = m.string(5), routeParams = m.stringMap(7),
            needAck = m.bool(9), pushServer = m.string(10),
        )
    }

    fun frame(type: String, payload: ByteArray, logId: Long = 0): ByteArray =
        ProtoWriter().long(2, logId).string(6, "pb").string(7, type).bytes(8, payload).toByteArray()

    fun heartbeat(roomId: Long, seq: Long) = frame("hb", ProtoWriter().long(1, roomId).long(2, seq).toByteArray())

    fun enterRoom(roomId: Long) = frame("im_enter_room",
        ProtoWriter().long(1, roomId).long(4, 12).string(5, "audience").string(9, "0").toByteArray())

    fun ack(logId: Long, internalExt: String) = frame("ack", internalExt.ifEmpty { "-" }.toByteArray(), logId)

    data class TtUser(val nickname: String, val uniqueId: String, val avatar: String)

    private fun user(m: ProtoMsg?): TtUser {
        if (m == null) return TtUser("", "", "")
        val avatar = m.msg(9)?.strings(1)?.firstOrNull().orEmpty()
        return TtUser(m.string(3), m.string(38), avatar)
    }

    private fun TtUser.display() = nickname.ifBlank { uniqueId.ifBlank { "Penonton" } }

    /** Ubah satu pesan webcast menjadi event. Pesan yang tidak relevan menghasilkan null. */
    fun decode(method: String, payload: ByteArray): Decoded? = runCatching {
        val m = ProtoMsg.parse(payload)
        when (method) {
            "WebcastChatMessage" -> {
                val u = user(m.msg(2))
                val text = m.string(3)
                if (text.isBlank()) null else Decoded.Event(LiveEvent(Platform.TIKTOK, EventType.CHAT, u.display(), text,
                    handle = u.uniqueId, avatar = u.avatar))
            }
            "WebcastGiftMessage" -> {
                val u = user(m.msg(7))
                val gift = m.msg(15)
                val repeat = m.int(5).coerceAtLeast(1)
                val repeatEnd = m.int(9) == 1
                val giftType = gift?.int(11) ?: 0
                // Gift tipe 1 bisa di-combo (streak): hanya catat saat streak selesai agar tidak dihitung ganda.
                if (giftType == 1 && !repeatEnd) return@runCatching null
                val name = gift?.string(16).orEmpty().ifBlank { "Gift #${m.long(2)}" }
                val diamond = (gift?.int(12) ?: 0).toLong()
                Decoded.Event(LiveEvent(Platform.TIKTOK, EventType.GIFT, u.display(), name, amount = repeat.toLong(),
                    amountText = "x$repeat", diamonds = diamond * repeat, handle = u.uniqueId, avatar = u.avatar,
                    giftImage = gift?.msg(1)?.strings(1)?.firstOrNull().orEmpty()))
            }
            "WebcastLikeMessage" -> {
                val u = user(m.msg(5))
                val count = m.int(2).coerceAtLeast(1)
                Decoded.Like(LiveEvent(Platform.TIKTOK, EventType.LIKE, u.display(), "menyukai live", amount = count.toLong(),
                    amountText = "x$count", handle = u.uniqueId, avatar = u.avatar), m.long(3))
            }
            "WebcastMemberMessage" -> {
                val u = user(m.msg(2))
                Decoded.Event(LiveEvent(Platform.TIKTOK, EventType.JOIN, u.display(), "bergabung", handle = u.uniqueId, avatar = u.avatar))
            }
            "WebcastSocialMessage" -> {
                val u = user(m.msg(2))
                // displayType (common.displayText.key) dan field action (4), diverifikasi dari pesan live asli:
                // follow  = "pm_main_follow_message_viewer_2", action 1
                // share   = "pm_mt_guidance_share", action 3
                // repost  = "ttlive_repost_commentsArea_repostedUsername_im", action 4
                val key = m.msg(1)?.msg(8)?.string(1).orEmpty().lowercase()
                val type = socialType(key, m.int(4))
                type?.let { Decoded.Event(LiveEvent(Platform.TIKTOK, it, u.display(), socialText(it), handle = u.uniqueId, avatar = u.avatar)) }
            }
            "WebcastSubNotifyMessage" -> {
                val u = user(m.msg(2))
                val month = m.long(4).coerceAtLeast(1)
                Decoded.Event(LiveEvent(Platform.TIKTOK, EventType.SUBSCRIBE, u.display(), "berlangganan", amount = month,
                    amountText = "$month bln", handle = u.uniqueId, avatar = u.avatar))
            }
            "WebcastRoomUserSeqMessage" -> m.long(3).takeIf { it > 0 }?.let { Decoded.Viewers(it) }
            "WebcastControlMessage" -> Decoded.Control(m.int(2))
            else -> null
        }
    }.getOrNull()

    const val SOCIAL_ACTION_FOLLOW = 1
    const val SOCIAL_ACTION_SHARE = 3
    const val SOCIAL_ACTION_REPOST = 4

    /** Jenis event dari WebcastSocialMessage. Teks displayType diutamakan; field action sebagai cadangan. */
    fun socialType(key: String, action: Int): EventType? = when {
        "repost" in key -> EventType.REPOST
        "follow" in key -> EventType.FOLLOW
        "share" in key -> EventType.SHARE
        key.isBlank() && action == SOCIAL_ACTION_FOLLOW -> EventType.FOLLOW
        key.isBlank() && action == SOCIAL_ACTION_SHARE -> EventType.SHARE
        key.isBlank() && action == SOCIAL_ACTION_REPOST -> EventType.REPOST
        else -> null
    }

    fun socialText(t: EventType) = when (t) {
        EventType.FOLLOW -> "mengikuti kamu"
        EventType.REPOST -> "memposting ulang live"
        else -> "membagikan live"
    }

    const val CONTROL_STREAM_ENDED = 3
    const val CONTROL_STREAM_SUSPENDED = 4

    /** Normalisasi input: "@nama", "nama", atau URL profil/live TikTok -> "nama". */
    fun normalizeUsername(input: String): String {
        var s = input.trim()
        Regex("tiktok\\.com/@([A-Za-z0-9._]+)").find(s)?.let { return it.groupValues[1] }
        s = s.removePrefix("@").substringBefore('/').substringBefore('?')
        return s.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
    }
}
