package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.tiktok.ProtoMsg
import com.zynnn.axwarestream.core.tiktok.TikTokLiveClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Riset protokol (opsional, -Dprobe=1): rekam jenis pesan & displayType asli dari live TikTok. */
class TikTokProbeTest {
    @Test fun probe() {
        assumeTrue(System.getenv("AXW_PROBE") == "1")
        val users = (System.getenv("AXW_USERS") ?: "").split(',').filter { it.isNotBlank() }
        val secs = (System.getenv("AXW_SECS") ?: "120").toLong()
        val out = File("build/probe").apply { mkdirs() }
        val methods = ConcurrentHashMap<String, Int>()
        val keys = ConcurrentHashMap<String, Int>()
        val samples = ConcurrentHashMap<String, String>()
        val types = ConcurrentHashMap<String, Int>()
        val examples = java.util.concurrent.CopyOnWriteArrayList<String>()
        val clients = users.mapNotNull { u ->
            val hub = EventHub()
            hub.addListener { e ->
                types.merge(e.type.name, 1, Int::plus)
                if (e.type.name in setOf("FOLLOW", "SHARE", "REPOST") && examples.size < 12) examples += "${e.type}: ${e.message}"
            }
            val c = TikTokLiveClient(hub)
            val r = runCatching { c.lookupRoom(u) }.getOrNull()
            println("$u live=${r?.live} viewers=${r?.viewers}")
            if (r?.live != true) return@mapNotNull null
            c.rawTap = { m, p ->
                methods.merge(m, 1, Int::plus)
                val key = runCatching { ProtoMsg.parse(p).msg(1)?.msg(8)?.string(1).orEmpty() }.getOrDefault("")
                val k = "$m|$key"
                if (keys.merge(k, 1, Int::plus) == 1 || m == "WebcastSocialMessage") {
                    if (!samples.containsKey(k)) { samples[k] = u; File(out, k.replace('|', '_').replace('/', '_') + ".bin").writeBytes(p) }
                }
            }
            c.connect(u); c
        }
        val end = System.currentTimeMillis() + secs * 1000
        while (System.currentTimeMillis() < end) Thread.sleep(1000)
        clients.forEach { it.disconnect() }
        println("METHODS " + methods.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        println("EVENTS " + types.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        examples.forEach { println("EX $it") }
        keys.entries.sortedBy { it.key }.forEach { println("KEY ${it.key} = ${it.value}") }
    }
}
