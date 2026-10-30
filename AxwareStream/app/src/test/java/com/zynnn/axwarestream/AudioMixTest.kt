package com.zynnn.axwarestream

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.zynnn.axwarestream.core.audio.AudioMeter
import com.zynnn.axwarestream.core.audio.AxwareAudioSource
import com.zynnn.axwarestream.core.audio.Pcm
import com.zynnn.axwarestream.core.audio.PcmReader
import com.zynnn.axwarestream.core.audio.PcmRing
import com.zynnn.axwarestream.core.stream.AudioSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Uji sumber audio Axware dengan pembaca PCM palsu yang berjalan real-time:
 * mic = nada 440 Hz, audio internal (game) = nada 1000 Hz. Hasil campuran dianalisis per frekuensi (Goertzel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioMixTest {
    private val sr = 44100

    /** Pembaca sinus real-time (stereo 16-bit), diberi jeda sesuai durasi blok seperti AudioRecord asli. */
    private class SineReader(val freq: Double, val amp: Double, val sr: Int, val stall: CountDownLatch? = null) : PcmReader {
        private var n = 0L
        @Volatile private var stopped = false
        override fun start() {}
        override fun stop() { stopped = true; stall?.countDown() }
        override fun read(buf: ByteArray, n: Int): Int {
            if (stall != null) { stall.await(10, TimeUnit.SECONDS); return -3 } // macet: tidak ada data sama sekali
            if (stopped) return -3
            val frames = n / 4
            for (i in 0 until frames) {
                val v = (sin(2 * PI * freq * (this.n + i) / sr) * amp * 32767).toInt()
                Pcm.put(buf, i * 4, v); Pcm.put(buf, i * 4 + 2, v)
            }
            this.n += frames
            Thread.sleep((frames * 1000L / sr))
            return frames * 4
        }
    }

    private class Collect : GetMicrophoneData {
        val out = ByteArrayOutputStream(); var frames = 0
        override fun inputPCMData(frame: Frame) { synchronized(this) { out.write(frame.buffer, frame.offset, frame.size); frames++ } }
        fun bytes() = synchronized(this) { out.toByteArray() }
    }

    /** Amplitudo relatif frekuensi [f] di kanal kiri (Goertzel). */
    private fun power(pcm: ByteArray, f: Double): Double {
        val frames = pcm.size / 4
        val k = 2 * cos(2 * PI * f / sr)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until frames) { val x = Pcm.sample(pcm, i * 4) / 32768.0; val s0 = x + k * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / frames * 2
    }

    private fun run(kind: AudioSourceKind, mic: PcmReader?, internal: PcmReader?, ms: Long, intVol: Float = 1f, internalFails: Boolean = false): Pair<ByteArray, Collect> {
        val src = AxwareAudioSource(kind, null, 1f, intVol,
            micFactory = { _, _ -> mic ?: error("tidak ada mic") },
            internalFactory = { _, _ -> if (internalFails) throw IllegalStateException("Perekam audio internal gagal dibuat") else internal!! })
        assertTrue(src.init(sr, true, false, false))
        val c = Collect()
        src.start(c)
        Thread.sleep(ms)
        val t0 = System.nanoTime(); src.stop()
        assertTrue("stop() harus cepat", (System.nanoTime() - t0) / 1e6 < 1500)
        return c.bytes() to c
    }

    @Test fun mixContainsMicAndGame() {
        val (pcm, c) = run(AudioSourceKind.MIX, SineReader(440.0, 0.3, sr), SineReader(1000.0, 0.3, sr), 1500)
        val seconds = pcm.size / 4.0 / sr
        assertTrue("durasi audio ${seconds}s", seconds in 1.0..1.8)
        val p440 = power(pcm, 440.0); val p1k = power(pcm, 1000.0); val p3k = power(pcm, 3000.0)
        println("MIX: frames=${c.frames} dur=${"%.2f".format(seconds)}s 440Hz=${"%.3f".format(p440)} 1000Hz=${"%.3f".format(p1k)} 3000Hz=${"%.4f".format(p3k)}")
        assertTrue("suara mic harus ada", p440 > 0.2)
        assertTrue("suara game (internal) harus ada", p1k > 0.2)
        assertTrue(p3k < 0.02)
        assertTrue(AudioMeter.levels.value.micMax > 0.25f)
        assertTrue(AudioMeter.levels.value.internalMax > 0.25f)
    }

    @Test fun internalVolumeApplied() {
        val (pcm, _) = run(AudioSourceKind.MIX, SineReader(440.0, 0.3, sr), SineReader(1000.0, 0.3, sr), 1000, intVol = 0.5f)
        val ratio = power(pcm, 1000.0) / power(pcm, 440.0)
        println("MIX volume internal 50%: rasio 1000Hz/440Hz=${"%.3f".format(ratio)}")
        assertEquals(0.5, ratio, 0.08)
    }

    @Test fun micKeepsFlowingWhenGameAudioStalls() {
        // Audio internal macet total (tidak ada data). Desain lama (baca mic lalu internal di satu thread) ikut macet.
        val (pcm, _) = run(AudioSourceKind.MIX, SineReader(440.0, 0.3, sr), SineReader(1000.0, 0.3, sr, CountDownLatch(1)), 1200)
        val seconds = pcm.size / 4.0 / sr
        println("MIX dengan internal macet: dur=${"%.2f".format(seconds)}s 440Hz=${"%.3f".format(power(pcm, 440.0))}")
        assertTrue("audio tetap mengalir walau internal macet", seconds > 0.8)
        assertTrue(power(pcm, 440.0) > 0.2)
    }

    @Test fun internalFailureFallsBackToMic() {
        val (pcm, _) = run(AudioSourceKind.MIX, SineReader(440.0, 0.3, sr), null, 800, internalFails = true)
        assertTrue(pcm.isNotEmpty()); assertTrue(power(pcm, 440.0) > 0.2)
        assertNotNull(AudioMeter.levels.value.internalError)
    }

    @Test fun internalOnly() {
        val (pcm, _) = run(AudioSourceKind.INTERNAL, null, SineReader(1000.0, 0.3, sr), 800)
        assertTrue(power(pcm, 1000.0) > 0.2); assertTrue(power(pcm, 440.0) < 0.02)
    }

    @Test fun ringBufferAndMixMath() {
        val r = PcmRing(16)
        r.write(ByteArray(8) { it.toByte() }, 8)
        val out = ByteArray(12)
        assertEquals(8, r.readFill(out, 12))
        assertEquals(7, out[7].toInt()); assertEquals(0, out[11].toInt())
        assertEquals(0, r.readFill(out, 4)); assertEquals(1L, r.underruns)
        r.write(ByteArray(24) { 1 }, 24) // lebih besar dari kapasitas: simpan 16 terbaru
        assertEquals(16, r.available())
        r.trimTo(8); assertEquals(8, r.available())
        // softClip: linear di bawah ambang, tidak pernah melewati 16-bit.
        assertEquals(1000, Pcm.softClip(1000)); assertTrue(Pcm.softClip(60000) <= 32767); assertTrue(Pcm.softClip(-60000) >= -32767)
        val a = ByteArray(4); val b = ByteArray(4); val d = ByteArray(4)
        Pcm.put(a, 0, 10000); Pcm.put(b, 0, 5000); Pcm.mix(a, 1f, b, 2f, d, 4)
        assertEquals(20000, Pcm.sample(d, 0))
    }
}
