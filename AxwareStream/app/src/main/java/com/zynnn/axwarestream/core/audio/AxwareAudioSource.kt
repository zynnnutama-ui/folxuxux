package com.zynnn.axwarestream.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import com.zynnn.axwarestream.core.stream.AudioSourceKind

/** Sumber PCM yang bisa dibaca (AudioRecord asli, atau palsu untuk pengujian). */
interface PcmReader {
    /** Baca blok PCM 16-bit; mengembalikan jumlah byte (negatif = error). Boleh memblok. */
    fun read(buf: ByteArray, n: Int): Int
    fun start()
    /** Hentikan rekaman (membuka blokir read). */
    fun stop()
    fun release() {}
}

class AudioRecordReader(private val rec: AudioRecord) : PcmReader {
    override fun read(buf: ByteArray, n: Int) = rec.read(buf, 0, n)
    override fun start() = rec.startRecording()
    override fun stop() { runCatching { rec.stop() } }
    override fun release() { runCatching { rec.release() } }
}

/**
 * Sumber audio Axware: Mikrofon, Audio internal (game), atau Mikrofon + internal.
 *
 * Berbeda dengan MixAudioSource bawaan RootEncoder yang membaca mic lalu internal secara bergantian
 * di satu thread (bila salah satu tersendat, keduanya ikut tersendat / hening), di sini:
 *  - Audio internal dibaca di thread sendiri dan ditampung di ring buffer.
 *  - Mikrofon menjadi "jam" utama; tiap blok mic dicampur dengan isi ring buffer (kurang = hening).
 *  - Level tiap sumber dikirim ke [AudioMeter] sesudah pengali volume perseptual supaya meter
 *    mencerminkan level nyata yang masuk ke siaran.
 *  - Bila audio internal gagal dibuat, siaran tetap jalan dengan mic dan alasannya dilaporkan.
 */
class AxwareAudioSource(
    private val kind: AudioSourceKind,
    private val projection: MediaProjection?,
    @Volatile var micVolume: Float = 1f,
    @Volatile var internalVolume: Float = 1f,
    /** Pengganti pembuat reader (untuk pengujian). null = AudioRecord asli. */
    private val micFactory: ((Int, Boolean) -> PcmReader)? = null,
    private val internalFactory: ((Int, Boolean) -> PcmReader)? = null,
) : AudioSource() {

    @Volatile private var running = false
    @Volatile var muted = false
    private var micThread: Thread? = null
    private var intThread: Thread? = null
    private var mic: PcmReader? = null
    private var internal: PcmReader? = null
    private var ring: PcmRing? = null

    val usesInternal get() = kind != AudioSourceKind.MIC
    val usesMic get() = kind != AudioSourceKind.INTERNAL

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        if (usesInternal && micFactory == null && internalFactory == null) {
            require(Build.VERSION.SDK_INT >= 29) { "Audio internal butuh Android 10+" }
            requireNotNull(projection) { "Audio internal butuh izin rekam layar" }
        }
        val min = if (micFactory == null) AudioRecord.getMinBufferSize(sampleRate, channelMask(isStereo), AudioFormat.ENCODING_PCM_16BIT) else 1
        require(min > 0) { "Format audio $sampleRate Hz tidak didukung" }
        return true
    }

    private fun channelMask(stereo: Boolean) = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    @SuppressLint("MissingPermission")
    private fun buildMic(sr: Int, stereo: Boolean): PcmReader {
        micFactory?.let { return it(sr, stereo) }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(channelMask(stereo)).build()
        val min = AudioRecord.getMinBufferSize(sr, channelMask(stereo), AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, CHUNK) * 4).build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { rec.release(); "Mikrofon tidak bisa dibuka" }
        return AudioRecordReader(rec)
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun buildInternal(sr: Int, stereo: Boolean): PcmReader {
        internalFactory?.let { return it(sr, stereo) }
        val mp = projection ?: error("Izin rekam layar belum ada")
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(channelMask(stereo)).build()
        val min = AudioRecord.getMinBufferSize(sr, channelMask(stereo), AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, CHUNK) * 4).build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { rec.release(); "Perekam audio internal gagal dibuat" }
        return AudioRecordReader(rec)
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (running) return
        val sr = sampleRate; val st = isStereo
        AudioMeter.reset(usesMic, usesInternal)
        if (usesInternal) {
            internal = runCatching { buildInternal(sr, st).also { r -> runCatching { r.start() }.onFailure { r.release(); throw it } } }.getOrElse { e ->
                Log.e(TAG, "internal audio", e); AudioMeter.error(internal = e.message ?: "Audio internal gagal"); null
            }
        }
        if (usesMic || internal == null) {
            mic = runCatching { buildMic(sr, st).also { r -> runCatching { r.start() }.onFailure { r.release(); throw it } } }.getOrElse { e ->
                Log.e(TAG, "mic", e); AudioMeter.error(mic = e.message ?: "Mikrofon gagal"); null
            }
        }
        if (mic == null && internal == null) throw IllegalStateException("Tidak ada sumber audio yang bisa dibuka")
        running = true
        val m = mic; val i = internal
        when {
            m != null && i != null -> {
                // Campuran: internal -> ring buffer, mic = jam utama.
                val r = PcmRing(bytesPerSec(sr, st)).also { ring = it }
                intThread = thread("AxInternal") { loopInternalToRing(i, r) }
                micThread = thread("AxMix") { loopMix(m, r, sr, st) }
            }
            m != null -> micThread = thread("AxMic") { loopSingle(m, isMic = true) }
            i != null -> intThread = thread("AxInternal") { loopSingle(i, isMic = false) }
        }
    }

    private fun bytesPerSec(sr: Int, st: Boolean) = sr * 2 * (if (st) 2 else 1)

    private fun thread(name: String, body: () -> Unit) = Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        runCatching(body).onFailure { Log.e(TAG, "$name berhenti", it) }
    }, name).also { it.start() }

    private fun emit(buf: ByteArray, n: Int) {
        val data = if (muted) ByteArray(n) else buf.copyOf(n)
        getMicrophoneData?.inputPCMData(Frame(data, 0, n, TimeUtils.getCurrentTimeMicro()))
    }

    private fun loopSingle(src: PcmReader, isMic: Boolean) {
        val buf = ByteArray(CHUNK)
        var meterTick = 0
        while (running) {
            val n = src.read(buf, CHUNK)
            if (n <= 0) { if (n < 0) Thread.sleep(5); continue }
            Pcm.applyVolume(buf, n, if (isMic) micVolume else internalVolume)
            if (++meterTick % 2 == 0) {
                if (isMic) AudioMeter.mic(Pcm.rms(buf, n), Pcm.peak(buf, n)) else AudioMeter.internal(Pcm.rms(buf, n), Pcm.peak(buf, n))
            }
            emit(buf, n)
        }
    }

    private fun loopInternalToRing(src: PcmReader, r: PcmRing) {
        val buf = ByteArray(CHUNK)
        while (running) {
            val n = src.read(buf, CHUNK)
            if (n <= 0) { if (n < 0) Thread.sleep(5); continue }
            val g = internalVolume * internalVolume
            AudioMeter.internal(Pcm.rms(buf, n) * g, Pcm.peak(buf, n) * g)
            r.write(buf, n)
        }
    }

    private fun loopMix(micSrc: PcmReader, r: PcmRing, sr: Int, st: Boolean) {
        val micBuf = ByteArray(CHUNK); val intBuf = ByteArray(CHUNK); val out = ByteArray(CHUNK)
        val maxQueue = bytesPerSec(sr, st) / 5 // maks ±200 ms jeda audio internal
        while (running) {
            val n = micSrc.read(micBuf, CHUNK)
            if (n <= 0) { if (n < 0) Thread.sleep(5); continue }
            r.trimTo(maxQueue + n)
            r.readFill(intBuf, n)
            val g = micVolume * micVolume
            AudioMeter.mic(Pcm.rms(micBuf, n) * g, Pcm.peak(micBuf, n) * g)
            Pcm.mix(micBuf, micVolume, intBuf, internalVolume, out, n)
            emit(out, n)
        }
    }

    override fun stop() {
        running = false
        mic?.stop(); internal?.stop() // membuka blokir read() di thread audio
        listOf(micThread, intThread).forEach { runCatching { it?.join(500) } }
        getMicrophoneData = null
        mic?.release(); internal?.release()
        mic = null; internal = null; micThread = null; intThread = null; ring = null
        AudioMeter.stopped()
    }

    override fun isRunning() = running

    override fun release() {}

    companion object {
        private const val TAG = "AxwareAudio"
        /** 4096 byte = 1024 frame stereo 16-bit ≈ 23 ms @44,1 kHz (di bawah batas 8192 encoder AAC). */
        const val CHUNK = 4096
    }
}
