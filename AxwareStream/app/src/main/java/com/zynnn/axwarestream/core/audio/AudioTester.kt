package com.zynnn.axwarestream.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.projection.MediaProjection
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.zynnn.axwarestream.core.stream.AudioSourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tes sumber audio tanpa siaran: menjalankan [AxwareAudioSource] (mic + internal) persis seperti saat live,
 * menampilkan level tiap sumber, memutar nada tes (untuk membuktikan audio internal tertangkap),
 * dan merekam hasil campuran supaya bisa didengar ulang.
 */
object AudioTester {
    enum class Phase { IDLE, RUNNING, DONE }
    data class State(val phase: Phase = Phase.IDLE, val seconds: Int = 0, val toneSent: Boolean = false,
                     val recordedMs: Int = 0, val error: String? = null)

    const val SAMPLE_RATE = 44100
    private const val MAX_BYTES = SAMPLE_RATE * 4 * 10 // simpan 10 detik terakhir

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @JvmField var source: AxwareAudioSource? = null
    private var recording = ByteArrayOutputStream()
    private var startedAt = 0L
    private var track: AudioTrack? = null

    private val sink = object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) {
            synchronized(this@AudioTester) {
                if (recording.size() < MAX_BYTES) recording.write(frame.buffer, frame.offset, frame.size)
            }
            val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (secs != _state.value.seconds) _state.value = _state.value.copy(seconds = secs)
        }
    }

    val running get() = _state.value.phase == Phase.RUNNING

    /** Mulai tes dengan sumber [kind] (mic+internal disarankan). */
    fun start(kind: AudioSourceKind, projection: MediaProjection?, micVol: Float, intVol: Float) {
        stop()
        synchronized(this) { recording = ByteArrayOutputStream() }
        val src = AxwareAudioSource(kind, projection, micVol, intVol)
        try {
            src.init(SAMPLE_RATE, true, false, false)
            startedAt = System.currentTimeMillis()
            _state.value = State(Phase.RUNNING)
            src.start(sink)
            source = src
        } catch (e: Exception) {
            _state.value = State(Phase.IDLE, error = e.message ?: "Gagal memulai tes audio")
            AudioMeter.stopped()
        }
    }

    fun stop() {
        val src = source ?: return
        source = null
        runCatching { src.stop() }
        val ms = synchronized(this) { recording.size() * 1000 / (SAMPLE_RATE * 4) }
        _state.value = _state.value.copy(phase = Phase.DONE, recordedMs = ms)
    }

    /** Nada 660 Hz 2 detik lewat speaker dengan USAGE_GAME (sama seperti suara game). */
    fun playTone() {
        val n = SAMPLE_RATE * 2
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val env = minOf(1.0, i / 2000.0, (n - i) / 2000.0)
            val v = (sin(2 * PI * 660 * i / SAMPLE_RATE) * 0.35 * env * 32767).toInt().toShort()
            pcm[2 * i] = v; pcm[2 * i + 1] = v
        }
        play(pcm, AudioAttributes.USAGE_GAME)
        _state.value = _state.value.copy(toneSent = true)
    }

    /** Putar ulang hasil rekaman campuran (setelah tes dihentikan, agar tidak terekam ulang). */
    fun playRecording() {
        val bytes = synchronized(this) { recording.toByteArray() }
        if (bytes.isEmpty()) return
        val pcm = ShortArray(bytes.size / 2) { Pcm.sample(bytes, it * 2).toShort() }
        play(pcm, AudioAttributes.USAGE_MEDIA)
    }

    private fun play(pcm: ShortArray, usage: Int) {
        runCatching { track?.stop(); track?.release() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    /** Ringkasan hasil tes untuk ditampilkan ke pengguna. */
    fun verdict(l: AudioLevels, s: State): String? {
        if (s.phase == Phase.IDLE) return null
        l.internalError?.let { return "Audio internal tidak bisa dibuka: $it" }
        if (s.toneSent && s.seconds >= 3 && l.internalMax < 0.01f)
            return "Nada tes tidak tertangkap. Perangkat/ROM ini memblokir perekaman audio internal untuk aplikasi pihak ketiga, " +
                "atau audio sedang lewat Bluetooth/panggilan. Matikan Bluetooth lalu coba lagi."
        if (s.toneSent && l.internalMax >= 0.01f)
            return "Audio internal berfungsi (nada tes tertangkap). Bila suara game tetap hening, game tersebut melarang " +
                "suaranya direkam (pengaturan dari pembuat game) atau voice chat game sedang aktif."
        return null
    }
}
