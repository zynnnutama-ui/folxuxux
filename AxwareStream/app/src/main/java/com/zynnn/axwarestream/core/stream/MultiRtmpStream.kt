package com.zynnn.axwarestream.core.stream

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * Satu komposit + satu encode, dikirim ke banyak server RTMP/RTMPS sekaligus.
 * Setiap tujuan punya koneksi, status, dan auto-reconnect sendiri: jika satu platform putus,
 * yang lain tetap live.
 */
class MultiRtmpStream(
    context: Context,
    videoSource: VideoSource,
    audioSource: AudioSource,
    private val listener: Listener,
) : StreamBase(context, videoSource, audioSource) {

    interface Listener {
        fun onDestinationState(id: String, state: DestState, detail: String)
        fun onDestinationBitrate(id: String, bps: Long)
    }

    enum class DestState { CONNECTING, LIVE, RECONNECTING, FAILED, STOPPED }

    private class Target(val id: String, val url: String, val client: RtmpClient, val streamClient: RtmpStreamClient)

    private val targets = ArrayList<Target>()
    private val lock = Any()
    private var sampleRate = 44100
    private var isStereo = true
    private var vCodec = VideoCodec.H264
    private var aCodec = AudioCodec.AAC
    private var width = 1280
    private var height = 720
    private var fps = 30

    private val keyframeListener = object : StreamClientListener {
        override fun onRequestKeyframe() { requestKeyframe() }
    }

    /** Wajib dipanggil sebelum [startAll] agar ukuran video di metadata RTMP benar. */
    fun setOutputInfo(width: Int, height: Int, fps: Int) {
        this.width = width; this.height = height; this.fps = fps
    }

    /** Mulai siaran ke semua tujuan. destinations: pasangan (id, urlLengkap). */
    fun startAll(destinations: List<Pair<String, String>>) {
        require(destinations.isNotEmpty()) { "Minimal satu tujuan streaming harus aktif" }
        synchronized(lock) {
            targets.forEach { it.client.disconnect() }
            targets.clear()
            destinations.forEach { (id, url) ->
                val checker = object : ConnectChecker {
                    override fun onConnectionStarted(url: String) = listener.onDestinationState(id, DestState.CONNECTING, "Menghubungkan…")
                    override fun onConnectionSuccess() {
                        requestKeyframe()
                        listener.onDestinationState(id, DestState.LIVE, "Live")
                    }
                    override fun onConnectionFailed(reason: String) {
                        val t = synchronized(lock) { targets.firstOrNull { it.id == id } }
                        if (t != null && isStreaming && t.streamClient.reTry(3000, reason)) {
                            listener.onDestinationState(id, DestState.RECONNECTING, "Menyambung ulang: $reason")
                        } else {
                            listener.onDestinationState(id, DestState.FAILED, reason)
                        }
                    }
                    override fun onNewBitrate(bitrate: Long) = listener.onDestinationBitrate(id, bitrate)
                    override fun onDisconnect() = listener.onDestinationState(id, DestState.STOPPED, "Terputus")
                    override fun onAuthError() = listener.onDestinationState(id, DestState.FAILED, "Autentikasi ditolak (cek stream key)")
                    override fun onAuthSuccess() {}
                }
                val client = RtmpClient(checker)
                val sc = RtmpStreamClient(client, keyframeListener)
                sc.setReTries(10)
                client.setVideoCodec(vCodec)
                client.setAudioCodec(aCodec)
                client.setAudioInfo(sampleRate, isStereo)
                client.setVideoResolution(width, height)
                client.setFps(fps)
                targets.add(Target(id, url, client, sc))
            }
        }
        // startStream -> startStreamImp (menghubungkan semua klien) -> menyalakan source & encoder.
        startStream("multi")
    }

    fun stopAll() {
        if (isStreaming) stopStream()
    }

    /** Hentikan satu tujuan saja; stream ke tujuan lain tetap berjalan. */
    fun stopDestination(id: String) {
        val t = synchronized(lock) { targets.firstOrNull { it.id == id }?.also { targets.remove(it) } } ?: return
        t.client.disconnect()
        listener.onDestinationState(id, DestState.STOPPED, "Dihentikan")
        val empty = synchronized(lock) { targets.isEmpty() }
        if (empty) stopAll()
    }

    fun activeDestinationIds(): List<String> = synchronized(lock) { targets.map { it.id } }

    fun bytesSent(): Long = synchronized(lock) { targets.sumOf { it.client.bytesSend } }

    override fun startStreamImp(endPoint: String) {
        synchronized(lock) { targets.forEach { it.client.connect(it.url) } }
    }

    override fun stopStreamImp() {
        val list = synchronized(lock) { targets.toList().also { targets.clear() } }
        list.forEach {
            it.client.disconnect()
            listener.onDestinationState(it.id, DestState.STOPPED, "Selesai")
        }
    }

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
        this.sampleRate = sampleRate; this.isStereo = isStereo
        synchronized(lock) { targets.forEach { it.client.setAudioInfo(sampleRate, isStereo) } }
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        synchronized(lock) { targets.forEach { it.client.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate()) } }
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) { targets.forEach { it.client.sendVideo(videoBuffer.duplicate(), info) } }
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) { targets.forEach { it.client.sendAudio(audioBuffer.duplicate(), info) } }
    }

    override fun setVideoCodecImp(codec: VideoCodec) {
        vCodec = codec
        synchronized(lock) { targets.forEach { it.client.setVideoCodec(codec) } }
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        aCodec = codec
        synchronized(lock) { targets.forEach { it.client.setAudioCodec(codec) } }
    }

    override fun getStreamClient(): StreamBaseClient =
        synchronized(lock) { targets.firstOrNull()?.streamClient }
            ?: throw IllegalStateException("Belum ada tujuan streaming aktif")
}
