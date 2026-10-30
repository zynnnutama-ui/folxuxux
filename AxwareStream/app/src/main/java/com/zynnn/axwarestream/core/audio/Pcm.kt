package com.zynnn.axwarestream.core.audio

import kotlin.math.sqrt

/** Utilitas PCM 16-bit little-endian (murni Kotlin, bisa diuji di JVM). */
object Pcm {
    fun sample(b: ByteArray, i: Int): Int = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()

    fun put(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xFF).toByte(); b[i + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** Batas lembut agar campuran keras tidak pecah (clip) kasar. Linear sampai ±24576. */
    fun softClip(x: Int): Int {
        val t = 24576
        if (x in -t..t) return x
        val sign = if (x < 0) -1 else 1
        val over = (kotlin.math.abs(x) - t).toDouble()
        val room = (32767 - t).toDouble()
        val y = t + room * (over / (over + room))
        return sign * y.toInt().coerceAtMost(32767)
    }

    /**
     * Kurva gain perseptual kuadratik (v * v):
     * Audio internal game dari AudioPlaybackCapture terekam pada skala penuh (0 dBFS),
     * sedangkan suara bicara mikrofon berada di sekitar -20 dBFS. Kurva kuadratik membuat
     * pengecilan volume terasa alami di telinga (mis. 50% = 0.25x / -12 dB, 30% = 0.09x / -21 dB,
     * 20% = 0.04x / -28 dB, 10% = 0.01x / -40 dB, 100% = 1.0x / 0 dB, 200% = 4.0x / +12 dB).
     */
    fun gain(vol: Float): Float = vol * vol

    /** dst = soft(mic * gain(micVol) + internal * gain(intVol)), [n] byte. */
    fun mix(mic: ByteArray, micVol: Float, internal: ByteArray, intVol: Float, dst: ByteArray, n: Int) {
        val mg = micVol * micVol
        val ig = intVol * intVol
        var i = 0
        while (i + 1 < n) {
            val v = (sample(mic, i) * mg + sample(internal, i) * ig).toInt()
            put(dst, i, softClip(v))
            i += 2
        }
    }

    fun applyVolume(b: ByteArray, n: Int, vol: Float) {
        val g = vol * vol
        if (g == 1f) return
        var i = 0
        while (i + 1 < n) { put(b, i, softClip((sample(b, i) * g).toInt())); i += 2 }
    }

    /** Level RMS 0..1 (1 = skala penuh). */
    fun rms(b: ByteArray, n: Int): Float {
        if (n < 2) return 0f
        var acc = 0.0; var c = 0; var i = 0
        while (i + 1 < n) { val s = sample(b, i).toDouble(); acc += s * s; c++; i += 2 }
        return (sqrt(acc / c) / 32768.0).toFloat()
    }

    /** Level puncak 0..1. */
    fun peak(b: ByteArray, n: Int): Float {
        var m = 0; var i = 0
        while (i + 1 < n) { val s = kotlin.math.abs(sample(b, i)); if (s > m) m = s; i += 2 }
        return m / 32768f
    }
}

/**
 * Ring buffer byte thread-safe. Penulis (thread audio internal) dan pembaca (thread mic)
 * tidak saling menunggu: bila data kurang, pembaca mendapat nol (hening), bukan macet.
 */
class PcmRing(capacity: Int) {
    private val buf = ByteArray(capacity)
    private var r = 0
    private var w = 0
    private var size = 0
    @Volatile var underruns = 0L; private set
    @Volatile var dropped = 0L; private set

    @Synchronized fun available() = size

    @Synchronized fun write(src: ByteArray, n: Int) {
        var len = n; var off = 0
        if (len > buf.size) { off = len - buf.size; len = buf.size }
        val overflow = size + len - buf.size
        if (overflow > 0) { r = (r + overflow) % buf.size; size -= overflow; dropped += overflow }
        var i = 0
        while (i < len) {
            val chunk = minOf(len - i, buf.size - w)
            System.arraycopy(src, off + i, buf, w, chunk)
            w = (w + chunk) % buf.size; i += chunk
        }
        size += len
    }

    /** Baca tepat [n] byte; kekurangan diisi nol. Mengembalikan jumlah byte asli yang terbaca. */
    @Synchronized fun readFill(dst: ByteArray, n: Int): Int {
        val take = minOf(n, size) and 0x7FFFFFFC // tetap selaras frame stereo 16-bit
        var i = 0
        while (i < take) {
            val chunk = minOf(take - i, buf.size - r)
            System.arraycopy(buf, r, dst, i, chunk)
            r = (r + chunk) % buf.size; i += chunk
        }
        size -= take
        if (take < n) { java.util.Arrays.fill(dst, take, n, 0); if (take == 0) underruns++ }
        return take
    }

    /** Buang data lama bila antrean lebih dari [maxBytes] agar jeda (latency) tetap kecil. */
    @Synchronized fun trimTo(maxBytes: Int) {
        if (size > maxBytes) {
            val drop = (size - maxBytes) and 0x7FFFFFFC
            r = (r + drop) % buf.size; size -= drop; dropped += drop
        }
    }

    @Synchronized fun clear() { r = 0; w = 0; size = 0 }
}
