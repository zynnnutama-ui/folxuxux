package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.net.SpeedTest
import com.zynnn.axwarestream.core.tiktok.TikTokLiveClient
import com.zynnn.axwarestream.core.tiktok.TikTokState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Uji NYATA ke TikTok LIVE (butuh internet): mencari akun yang sedang live, lalu menyambungkan
 * TikTokLiveClient yang sama persis dengan yang dipakai aplikasi dan menunggu event asli.
 */
class TikTokLiveTest {

    private val candidates = listOf("aljazeeraenglish", "nbcnews", "skynews", "cnn", "abcnews", "foxnews", "cbsnews",
        "tv5monde", "dw_news", "france24_en", "bloombergbusiness", "kompastv", "cnnindonesia", "metrotv", "tvonenews")

    @Test fun realTikTokLiveEvents() {
        val hub = EventHub()
        val client = TikTokLiveClient(hub)
        val live = candidates.asSequence().mapNotNull { u ->
            runCatching { client.lookupRoom(u) }.onFailure { println("$u: ${it.message}") }.getOrNull()?.takeIf { it.live }?.let { u to it }
        }.firstOrNull()
        assumeTrue("Tidak ada akun kandidat yang sedang live di TikTok", live != null)
        val (user, room) = live!!
        println("TikTok live: @$user room=${room.roomId} viewers=${room.viewers} title=${room.title}")

        val got = CopyOnWriteArrayList<LiveEvent>()
        hub.addListener { got += it }
        client.connect(user)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline && (client.messagesReceived < 30 || hub.stats.value.totalViewers == 0L)) Thread.sleep(500)
        Thread.sleep(5000)
        val st = client.status.value
        val stats = hub.stats.value
        client.disconnect()
        println("Status: ${st.state} ${st.detail}; pesan protokol=${client.messagesReceived}")
        println("Event per jenis: " + got.groupingBy { it.type }.eachCount())
        println("Contoh komentar: " + got.filter { it.type == EventType.CHAT }.take(5).joinToString { "${it.user}: ${it.message}" })
        println("Statistik: penonton=${stats.totalViewers} like=${stats.likeCount} gift=${stats.giftCount} diamond=${stats.diamondTotal}")
        assertEquals(TikTokState.CONNECTED, st.state)
        assertTrue("Harus menerima pesan TikTok asli", client.messagesReceived > 0)
        assertTrue("Jumlah penonton harus terbaca", stats.totalViewers > 0)
        assertTrue("Harus ada event asli dari TikTok", got.any { it.platform == com.zynnn.axwarestream.core.events.Platform.TIKTOK })
    }

    @Test fun uploadSpeedTest() {
        val kbps = SpeedTest.measureUploadKbps(2 * 1024 * 1024)
        println("Upload terukur: $kbps kbps")
        assertTrue(kbps > 100)
    }
}
