package com.zynnn.axwarestream

import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Uji NYATA jalur multistream RTMP: video H.264 + audio AAC asli dikirim oleh RtmpClient (library yang sama
 * dengan aplikasi, konfigurasi sama dengan MultiRtmpStream) ke DUA server RTMP sekaligus (ffmpeg -listen),
 * lalu file hasil terima di server diperiksa dengan ffprobe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RtmpFanoutTest {

    private fun run(vararg cmd: String): Process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    private fun hasFfmpeg() = runCatching { run("ffmpeg", "-version").waitFor() == 0 }.getOrDefault(false)

    /** Pisah Annex-B menjadi NAL unit (tanpa start code). */
    private fun nals(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>() // (posisi data, panjang start code)
        var i = 0
        while (i + 3 < b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
                if (b[i + 2] == 1.toByte()) { starts += (i + 3) to 3; i += 3; continue }
                if (b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) { starts += (i + 4) to 4; i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { k, (pos, _) ->
            val end = if (k + 1 < starts.size) starts[k + 1].first - starts[k + 1].second else b.size
            b.copyOfRange(pos, end)
        }
    }

    private fun annexB(vararg n: ByteArray): ByteBuffer {
        val out = java.io.ByteArrayOutputStream()
        n.forEach { out.write(byteArrayOf(0, 0, 0, 1)); out.write(it) }
        return ByteBuffer.wrap(out.toByteArray())
    }

    @Test fun h264AacToTwoRtmpServers() {
        assumeTrue("ffmpeg tidak tersedia", hasFfmpeg())
        val dir = Files.createTempDirectory("rtmp").toFile()
        val h264 = File(dir, "v.h264"); val aac = File(dir, "a.aac")
        assertEquals(0, run("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "6", "-c:v", "libx264",
            "-profile:v", "baseline", "-bf", "0", "-g", "30", "-x264-params", "aud=1:scenecut=0", "-pix_fmt", "yuv420p", h264.path).waitFor())
        assertEquals(0, run("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-ac", "2", "-t", "6",
            "-c:a", "aac", "-b:a", "128k", aac.path).waitFor())

        // Dua "platform" RTMP lokal.
        val outs = listOf(File(dir, "dest1.flv"), File(dir, "dest2.flv"))
        val ports = listOf(19351, 19352)
        val servers = ports.mapIndexed { k, p ->
            run("ffmpeg", "-y", "-loglevel", "error", "-listen", "1", "-timeout", "30", "-i", "rtmp://127.0.0.1:$p/live/key$k", "-c", "copy", outs[k].path)
        }
        Thread.sleep(1500)

        // Pisahkan video menjadi access unit (dibatasi AUD, NAL type 9).
        val all = nals(h264.readBytes())
        val sps = all.first { (it[0].toInt() and 0x1F) == 7 }
        val pps = all.first { (it[0].toInt() and 0x1F) == 8 }
        val aus = ArrayList<List<ByteArray>>()
        var cur = ArrayList<ByteArray>()
        for (n in all) {
            val t = n[0].toInt() and 0x1F
            if (t == 9) { if (cur.isNotEmpty()) aus += cur; cur = ArrayList(); continue }
            if (t == 1 || t == 5) cur += n
        }
        if (cur.isNotEmpty()) aus += cur
        assertTrue(aus.size >= 150)

        // Frame AAC dari ADTS (lepas header 7/9 byte), 1024 sampel per frame.
        val ab = aac.readBytes(); val aframes = ArrayList<ByteArray>(); var p = 0
        while (p + 7 < ab.size) {
            val len = ((ab[p + 3].toInt() and 0x03) shl 11) or ((ab[p + 4].toInt() and 0xFF) shl 3) or ((ab[p + 5].toInt() and 0xE0) ushr 5)
            val hdr = if ((ab[p + 1].toInt() and 0x01) == 0) 9 else 7
            aframes += ab.copyOfRange(p + hdr, p + len); p += len
        }

        val connected = CountDownLatch(2)
        val failures = ArrayList<String>()
        val clients = ports.mapIndexed { k, port ->
            RtmpClient(object : ConnectChecker {
                override fun onConnectionStarted(url: String) {}
                override fun onConnectionSuccess() { connected.countDown() }
                override fun onConnectionFailed(reason: String) { synchronized(failures) { failures += reason } }
                override fun onNewBitrate(bitrate: Long) {}
                override fun onDisconnect() {}
                override fun onAuthError() {}
                override fun onAuthSuccess() {}
            }).apply {
                setVideoCodec(VideoCodec.H264); setAudioCodec(AudioCodec.AAC)
                setAudioInfo(44100, true); setVideoResolution(640, 360); setFps(30)
                setVideoInfo(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps), ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps), null)
                connect("rtmp://127.0.0.1:$port/live/key$k")
            }
        }
        // Callback ConnectChecker dikirim ke main looper (thread tes ini) -> jalankan looper sambil menunggu.
        val deadline = System.currentTimeMillis() + 20_000
        while (connected.count > 0 && failures.isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50)
        }
        assertTrue("Kedua server RTMP harus terhubung. Gagal: $failures", connected.count == 0L)

        // Kirim real-time ±5 detik: video 30 fps, audio 44100/1024 fps, ke kedua klien (fan-out seperti MultiRtmpStream).
        val t0 = System.nanoTime()
        var vi = 0; var ai = 0
        val durUs = 5_000_000L
        while (true) {
            val vts = vi * 1_000_000L / 30; val ats = ai * 1024L * 1_000_000L / 44100
            if (vts > durUs && ats > durUs) break
            if (vts <= ats && vi < aus.size) {
                val buf = annexB(*aus[vi].toTypedArray())
                val info = MediaCodec.BufferInfo().apply {
                    set(0, buf.remaining(), vts, if (aus[vi].any { (it[0].toInt() and 0x1F) == 5 }) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                }
                clients.forEach { it.sendVideo(buf.duplicate(), info) }
                vi++
            } else if (ai < aframes.size) {
                val buf = ByteBuffer.wrap(aframes[ai])
                val info = MediaCodec.BufferInfo().apply { set(0, buf.remaining(), ats, 0) }
                clients.forEach { it.sendAudio(buf.duplicate(), info) }
                ai++
            } else break
            val wait = minOf(vts, ats) / 1000 - (System.nanoTime() - t0) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        repeat(30) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
        assertTrue("Tidak boleh ada kegagalan koneksi: $failures", failures.isEmpty())
        val sent = clients.map { it.bytesSend }
        clients.forEach { it.disconnect() }
        servers.forEach { it.waitFor(15, TimeUnit.SECONDS); if (it.isAlive) it.destroy() }
        println("Terkirim per tujuan (byte): $sent")

        outs.forEachIndexed { k, f ->
            assertTrue("File tujuan $k harus ada", f.exists() && f.length() > 50_000)
            val probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height:format=duration", "-of", "default=nw=1", f.path)
            val txt = probe.inputStream.bufferedReader().readText(); probe.waitFor()
            println("Tujuan $k (${f.length()} byte):\n$txt")
            assertTrue("Video H.264 diterima", txt.contains("codec_name=h264"))
            assertTrue("Audio AAC diterima", txt.contains("codec_name=aac"))
            assertTrue("Resolusi benar", txt.contains("width=640") && txt.contains("height=360"))
            val dur = Regex("duration=([0-9.]+)").find(txt)?.groupValues?.get(1)?.toDouble() ?: 0.0
            val dec = run("ffmpeg", "-v", "error", "-i", f.path, "-f", "null", "-")
            val decErr = dec.inputStream.bufferedReader().readText(); dec.waitFor()
            assertTrue("Stream harus dapat didekode tanpa error: $decErr", decErr.isBlank())
            assertTrue("Durasi ≥ 4 detik (dapat $dur)", dur >= 4.0)
        }
        dir.deleteRecursively()
    }
}
