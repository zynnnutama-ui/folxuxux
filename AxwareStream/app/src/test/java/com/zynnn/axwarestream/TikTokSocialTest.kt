package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.tiktok.TikTokProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dekode pesan follow / share / repost / penonton masuk dari payload TikTok LIVE ASLI
 * (direkam dari live @aljazeeraenglish 23-10-2026, identitas penonton diganti "Penonton ...").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TikTokSocialTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("tiktok/$name")!!.readBytes()

    private fun decode(method: String, file: String): LiveEvent {
        val d = TikTokProtocol.decode(method, res(file))
        assertTrue("hasil dekode $file: $d", d is TikTokProtocol.Decoded.Event)
        return (d as TikTokProtocol.Decoded.Event).event
    }

    @Test fun realFollowShareRepostJoin() {
        decode("WebcastSocialMessage", "social_follow.bin").let {
            assertEquals(EventType.FOLLOW, it.type); assertEquals("Penonton Follow", it.user); assertEquals("penonton_follow", it.handle)
            assertEquals("mengikuti kamu", it.message)
        }
        decode("WebcastSocialMessage", "social_share.bin").let {
            assertEquals(EventType.SHARE, it.type); assertEquals("Penonton Share", it.user); assertEquals("membagikan live", it.message)
        }
        decode("WebcastSocialMessage", "social_repost.bin").let {
            assertEquals(EventType.REPOST, it.type); assertEquals("Penonton Repost", it.user); assertEquals("penonton_repost", it.handle)
            assertEquals("memposting ulang live", it.message)
        }
        decode("WebcastMemberMessage", "member_join.bin").let {
            assertEquals(EventType.JOIN, it.type); assertEquals("Penonton Masuk", it.user); assertEquals("bergabung", it.message)
        }
    }

    @Test fun socialTypeRules() {
        assertEquals(EventType.REPOST, TikTokProtocol.socialType("ttlive_repost_commentsarea_repostedusername_im", 4))
        assertEquals(EventType.FOLLOW, TikTokProtocol.socialType("pm_main_follow_message_viewer_2", 1))
        assertEquals(EventType.SHARE, TikTokProtocol.socialType("pm_mt_guidance_share", 3))
        // Tanpa displayType: pakai field action.
        assertEquals(EventType.FOLLOW, TikTokProtocol.socialType("", 1))
        assertEquals(EventType.SHARE, TikTokProtocol.socialType("", 3))
        assertEquals(EventType.REPOST, TikTokProtocol.socialType("", 4))
        assertNull(TikTokProtocol.socialType("", 0))
        assertNull(TikTokProtocol.socialType("pm_something_else", 2))
    }

    @Test fun repostCountedEverywhere() {
        val hub = EventHub()
        hub.emit(decode("WebcastSocialMessage", "social_repost.bin"))
        hub.emit(decode("WebcastSocialMessage", "social_share.bin"))
        hub.emit(decode("WebcastMemberMessage", "member_join.bin"))
        val s = hub.stats.value
        assertEquals(1, s.repostCount); assertEquals(1, s.shareCount); assertEquals(1, s.joinCount)
        assertEquals(1, s.counter("REPOST")); assertEquals(1, s.toJson().getLong("REPOST"))
        assertEquals(5, s.leaderboard.first { it.user == "Penonton Repost" }.points)
        // Webhook / JSON eksternal juga mengenal tipe REPOST.
        assertEquals(EventType.REPOST, EventType.parse("repost"))
        assertEquals(EventType.REPOST, LiveEvent.fromWebhookJson(JSONObject("""{"type":"REPOST","user":"A"}""")).type)
    }
}
