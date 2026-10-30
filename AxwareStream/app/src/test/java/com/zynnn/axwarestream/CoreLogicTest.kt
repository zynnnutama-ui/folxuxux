package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import com.zynnn.axwarestream.core.overlay.ElementType
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.OverlayProject
import com.zynnn.axwarestream.core.overlay.Templates
import com.zynnn.axwarestream.core.stream.Destination
import com.zynnn.axwarestream.core.stream.UploadBudget
import com.zynnn.axwarestream.core.tiktok.ProtoMsg
import com.zynnn.axwarestream.core.tiktok.ProtoWriter
import com.zynnn.axwarestream.core.tiktok.TikTokProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji logika inti (murni JVM, tanpa jaringan). */
class CoreLogicTest {

    @Test fun normalizeUsername() {
        assertEquals("zynnn_173", TikTokProtocol.normalizeUsername("@zynnn_173"))
        assertEquals("zynnn_173", TikTokProtocol.normalizeUsername("  zynnn_173 "))
        assertEquals("zynnn_173", TikTokProtocol.normalizeUsername("https://www.tiktok.com/@zynnn_173/live?lang=id"))
        assertEquals("a.b_c", TikTokProtocol.normalizeUsername("a.b_c"))
    }

    @Test fun protoRoundTrip() {
        val inner = ProtoWriter().string(3, "Nick").string(38, "handle").toByteArray()
        val msg = ProtoWriter().bytes(2, inner).string(3, "halo 👋").long(7, 7699631452921154317L).toByteArray()
        val p = ProtoMsg.parse(msg)
        assertEquals("halo 👋", p.string(3)); assertEquals(7699631452921154317L, p.long(7))
        assertEquals("Nick", p.msg(2)!!.string(3)); assertEquals("handle", p.msg(2)!!.string(38))
    }

    @Test fun decodeSyntheticMessages() {
        val user = ProtoWriter().string(3, "Rina").string(38, "rina01").toByteArray()
        val chat = ProtoWriter().bytes(2, user).string(3, "Halo kak").toByteArray()
        val c = TikTokProtocol.decode("WebcastChatMessage", chat) as TikTokProtocol.Decoded.Event
        assertEquals(EventType.CHAT, c.event.type); assertEquals("Rina", c.event.user); assertEquals("rina01", c.event.handle)
        assertEquals("Halo kak", c.event.message)

        val gift = ProtoWriter().string(16, "Rose").long(12, 1).long(11, 1).toByteArray()
        val mid = ProtoWriter().bytes(7, user).long(5, 5).bytes(15, gift).toByteArray()
        assertNull("Streak belum selesai tidak boleh dihitung", TikTokProtocol.decode("WebcastGiftMessage", mid))
        val end = ProtoWriter().bytes(7, user).long(5, 5).long(9, 1).bytes(15, gift).toByteArray()
        val g = (TikTokProtocol.decode("WebcastGiftMessage", end) as TikTokProtocol.Decoded.Event).event
        assertEquals(EventType.GIFT, g.type); assertEquals("Rose", g.message); assertEquals(5L, g.amount); assertEquals(5L, g.diamonds)

        val like = ProtoWriter().bytes(5, user).long(2, 15).long(3, 1200).toByteArray()
        val l = TikTokProtocol.decode("WebcastLikeMessage", like) as TikTokProtocol.Decoded.Like
        assertEquals(15L, l.event.amount); assertEquals(1200L, l.total)

        val seq = ProtoWriter().long(3, 4321).toByteArray()
        assertEquals(4321L, (TikTokProtocol.decode("WebcastRoomUserSeqMessage", seq) as TikTokProtocol.Decoded.Viewers).count)
    }

    /** Dekode respons fetch ASLI dari live TikTok @aljazeeraenglish (direkam 22-10-2026, room 7699631452921154317). */
    @Test fun decodeRealCapturedFetch() {
        val bytes = javaClass.getResourceAsStream("/tiktok/fetch_aljazeera.bin")!!.readBytes()
        val res = TikTokProtocol.parseFetchResult(bytes)
        println("Pesan asli: " + res.messages.groupingBy { it.first }.eachCount())
        assertTrue(res.messages.isNotEmpty())
        assertTrue("Harus ada route params untuk websocket", res.routeParams.isNotEmpty())
        assertTrue(res.pushServer.startsWith("wss://"))
        val decoded = res.messages.mapNotNull { (m, p) -> TikTokProtocol.decode(m, p) }
        val chats = decoded.filterIsInstance<TikTokProtocol.Decoded.Event>().filter { it.event.type == EventType.CHAT }
        println("Komentar asli: " + chats.joinToString { "${it.event.user}: ${it.event.message}" })
        assertTrue("Harus ada komentar asli terdekode", chats.isNotEmpty())
        assertTrue(chats.all { it.event.user.isNotBlank() && it.event.message.isNotBlank() })
    }

    @Test fun webhookJson() {
        val e = LiveEvent.fromWebhookJson(JSONObject("""{"donator_name":"Sinta","amount":"Rp25.000","donator_message":"gas"}"""))
        assertEquals(EventType.DONATION, e.type); assertEquals(25000L, e.amount); assertEquals("Sinta", e.user); assertEquals("gas", e.message)
        val g = LiveEvent.fromWebhookJson(JSONObject("""{"platform":"tiktok","type":"gift","user":"A","amount":3,"message":"Rose"}"""))
        assertEquals(Platform.TIKTOK, g.platform); assertEquals(EventType.GIFT, g.type)
    }

    @Test fun eventHubTikTokStats() {
        val hub = EventHub()
        repeat(3) { hub.emit(LiveEvent(Platform.TIKTOK, EventType.CHAT, "a", "x")) }
        hub.emit(LiveEvent(Platform.TIKTOK, EventType.GIFT, "b", "Rose", amount = 5, diamonds = 5))
        hub.emit(LiveEvent(Platform.TIKTOK, EventType.GIFT, "c", "Finger Heart", amount = 2, diamonds = 10))
        hub.emit(LiveEvent(Platform.TIKTOK, EventType.JOIN, "d", "bergabung"))
        hub.emit(LiveEvent(Platform.TIKTOK, EventType.LIKE, "e", "", amount = 15))
        hub.setLikeTotal(1000)
        hub.setViewers(Platform.TIKTOK, 120)
        val s = hub.stats.value
        assertEquals(3L, s.chatCount); assertEquals(7L, s.giftCount); assertEquals(15L, s.diamondTotal)
        assertEquals(1000L, s.likeCount); assertEquals(1L, s.joinCount); assertEquals(120L, s.totalViewers)
        assertEquals(15L, s.counter("DIAMOND")); assertEquals(1L, s.counter("JOIN"))
        assertEquals("c", s.topGifters.first().user); assertEquals(10L, s.topGifters.first().diamonds)
        assertEquals("JOIN tidak masuk feed", 6, hub.recent().size)
        val json = s.toJson()
        assertEquals(15L, json.getLong("DIAMOND"))
        hub.reset(); assertEquals(0L, hub.stats.value.chatCount)
    }

    @Test fun overlayJsonRoundTrip() {
        val p = OverlayProject.default()
        val withAll = p.copy(scenes = p.scenes.map { s ->
            s.copy(elements = s.elements + ElementType.entries.map { OverlayElement.create(it, s.width, s.height) })
        })
        val back = OverlayProject.fromJson(JSONObject(withAll.toJson().toString()))
        assertEquals(withAll.scenes, back.scenes)
        assertEquals(withAll.activeSceneId, back.activeSceneId)
        assertEquals(4, Templates.builtIn().size)
    }

    @Test fun destinationValidationAndSplit() {
        val d = Destination(server = "rtmp://push-rtmp-l11-va01.tiktokcdn.com/game/", streamKey = "stream-123?expire=1&sign=ab")
        assertNull(d.validate())
        assertEquals("rtmp://push-rtmp-l11-va01.tiktokcdn.com/game/stream-123?expire=1&sign=ab", d.fullUrl())
        assertNotNull(Destination().validate())
        assertNotNull(Destination(server = "http://x.com/game/").validate())
        assertNotNull(Destination(server = "rtmp://x.com/game/", streamKey = "a b").validate())
        val split = Destination.splitFullUrl("rtmp://push.example.com/game/stream-9?sign=x")!!
        assertEquals("rtmp://push.example.com/game/", split.first); assertEquals("stream-9?sign=x", split.second)
        assertNull(Destination.splitFullUrl("https://x.com/a/b"))
    }

    @Test fun uploadBudget() {
        assertEquals(2500 + 128, UploadBudget.requiredKbps(2500, 128))
        assertEquals("OK", UploadBudget.verdict(5000, 10000))
        assertEquals("WARN", UploadBudget.verdict(9000, 10000))
        assertEquals("OVER", UploadBudget.verdict(12000, 10000))
        assertEquals("UNKNOWN", UploadBudget.verdict(12000, null))
    }
}
