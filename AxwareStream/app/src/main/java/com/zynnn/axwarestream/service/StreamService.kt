package com.zynnn.axwarestream.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.recording.RecordController
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.MainActivity
import com.zynnn.axwarestream.R
import com.zynnn.axwarestream.core.stream.AudioSourceKind
import com.zynnn.axwarestream.core.stream.Destination
import com.zynnn.axwarestream.core.stream.MultiRtmpStream
import com.zynnn.axwarestream.core.stream.MultiRtmpStream.DestState
import com.zynnn.axwarestream.core.stream.StreamSettings
import com.zynnn.axwarestream.core.stream.VideoSourceKind
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import com.zynnn.axwarestream.core.stream.GameLayoutFilterRender
import com.zynnn.axwarestream.core.audio.AudioTester
import com.zynnn.axwarestream.core.audio.AxwareAudioSource
import com.zynnn.axwarestream.core.stream.GameScreenSource
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DestStatus(val name: String, val state: DestState, val detail: String = "", val kbps: Long = 0)

data class LiveState(
    val prepared: Boolean = false,
    val source: VideoSourceKind = VideoSourceKind.CAMERA,
    val streaming: Boolean = false,
    val startedAt: Long = 0,
    val recording: Boolean = false,
    val micMuted: Boolean = false,
    val destinations: Map<String, DestStatus> = emptyMap(),
    val fps: Int = 0,
    val error: String? = null,
    val lastRecording: String? = null,
    /** Naik setiap stream dibuat ulang, agar UI memasang ulang pratinjau. */
    val generation: Long = 0,
)

/**
 * Foreground service yang memegang encoder & koneksi RTMP agar siaran tetap jalan
 * saat aplikasi di background (mis. sedang main game di Mode B layar).
 */
class StreamService : Service(), MultiRtmpStream.Listener {

    inner class LocalBinder : Binder() { val service: StreamService get() = this@StreamService }
    private val binder = LocalBinder()

    private val app get() = application as AxwareApp
    private var stream: MultiRtmpStream? = null
    private var projection: MediaProjection? = null
    private var activeSettings: StreamSettings? = null
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var overlayFilter: ImageFilterRender? = null
    private var layoutFilter: GameLayoutFilterRender? = null
    @Volatile private var captureSize: Pair<Int, Int> = 0 to 0
    private val scope = kotlinx.coroutines.MainScope()
    private var webLayers: com.zynnn.axwarestream.core.overlay.WebLayerHost? = null
    private var webWindow: android.view.WindowManager.LayoutParams? = null
    private var webJob: kotlinx.coroutines.Job? = null
    private var overlayThread: Thread? = null
    @Volatile private var overlayRunning = false
    private var recordFile: File? = null
    private val destNames = HashMap<String, String>()
    private var generation = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Perubahan volume mikrofon/internal & tata letak game berlaku langsung saat live maupun tes audio.
        scope.launch {
            app.settings.settings.collect { s ->
                ((stream?.audioSource ?: AudioTester.source) as? AxwareAudioSource)?.let {
                    it.micVolume = s.micVolume
                    it.internalVolume = s.internalVolume
                }
                val f = layoutFilter ?: return@collect
                val (w, h) = captureSize
                val a = activeSettings ?: return@collect
                if (w > 0 && h > 0) f.update(s.layout, a.outW, a.outH, w, h)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopLive(); stopSelf() }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ persiapan
    private fun buildStream(video: VideoSource, audio: AudioSource, s: StreamSettings): MultiRtmpStream {
        val st = MultiRtmpStream(applicationContext, video, audio, this)
        st.setVideoCodec(VideoCodec.H264) // TikTok LIVE menerima H.264 + AAC
        st.setAudioCodec(AudioCodec.AAC)
        val rotation = if (s.portrait) 90 else 0
        val okV = st.prepareVideo(s.width, s.height, s.videoKbps * 1000, s.fps, 2, rotation)
        val okA = st.prepareAudio(44100, true, s.audioKbps * 1000, echoCanceler = s.audio == AudioSourceKind.MIC,
            noiseSuppressor = s.audio == AudioSourceKind.MIC)
        if (!okV) throw IllegalStateException("Encoder video tidak mendukung ${s.resolution.label} ${s.fps}fps H.264")
        if (!okA) throw IllegalStateException("Encoder audio gagal disiapkan")
        st.setOutputInfo(s.outW, s.outH, s.fps)
        st.setFpsListener { fps -> _state.value = _state.value.copy(fps = fps) }
        return st
    }

    /** Siapkan mode kamera (untuk preview). Aman dipanggil berulang. */
    fun prepareCamera(s: StreamSettings): Result<Unit> = runCatching {
        if (_state.value.streaming) return@runCatching
        releaseStream()
        val cam = Camera2Source(applicationContext)
        val audio = AxwareAudioSource(AudioSourceKind.MIC, null, s.micVolume, s.internalVolume)
        val st = buildStream(cam, audio, s)
        stream = st
        activeSettings = s
        if (!s.frontCamera) runCatching { if (cam.getCameraFacing() == CameraHelper.Facing.FRONT) cam.switchCamera() }
        else runCatching { if (cam.getCameraFacing() == CameraHelper.Facing.BACK) cam.switchCamera() }
        _state.value = LiveState(prepared = true, source = VideoSourceKind.CAMERA, generation = ++generation)
    }.onFailure { _state.value = _state.value.copy(prepared = false, error = it.message) }

    private fun StreamSettings.encoderKey() = listOf(resolution, portrait, fps, videoKbps, audioKbps)

    /** Siapkan kamera hanya jika belum siap atau setelan encoder berubah. */
    fun ensureCamera(s: StreamSettings) {
        val cur = activeSettings
        val st = _state.value
        if (st.streaming) return
        if (stream != null && st.prepared && st.source == VideoSourceKind.CAMERA && cur != null && cur.encoderKey() == s.encoderKey()) return
        prepareCamera(s)
    }

    fun attachPreview(view: SurfaceView) {
        val st = stream ?: return
        runCatching { st.startPreview(view, autoHandle = true) }
    }

    fun detachPreview() {
        val st = stream ?: return
        runCatching { if (st.isOnPreview) st.stopPreview(true) }
    }

    // ------------------------------------------------------------------ siaran
    private fun goForeground(screen: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        type = type or if (screen) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, AxwareApp.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Axware Stream sedang live")
            .setContentText("Siaran ke TikTok LIVE")
            .setOngoing(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Akhiri live", stop)
            .build()
    }

    private fun destPairs(dests: List<Destination>): List<Pair<String, String>> {
        destNames.clear()
        dests.forEach { destNames[it.id] = it.name }
        return dests.map { it.id to it.fullUrl() }
    }

    /** Mulai live mode kamera. */
    fun startCameraLive(dests: List<Destination>): Result<Unit> = runCatching {
        val s = app.settings.settings.value
        if (stream == null || activeSettings?.encoderKey() != s.encoderKey() || _state.value.source != VideoSourceKind.CAMERA) prepareCamera(s).getOrThrow()
        val st = stream ?: error("Stream belum siap")
        startService(Intent(this, StreamService::class.java))
        goForeground(screen = false)
        if (s.overlayInStream) startOverlayFilter(st, s)
        val pairs = destPairs(dests)
        _state.value = _state.value.copy(destinations = dests.associate { it.id to DestStatus(it.name, DestState.CONNECTING) }, error = null)
        st.startAll(pairs)
        if (s.recordLocal) startRecording()
        _state.value = _state.value.copy(streaming = true, startedAt = System.currentTimeMillis())
    }.onFailure { e -> _state.value = _state.value.copy(error = e.message); stopForegroundSafe() }

    /** Mulai live mode layar (game). Harus dipanggil setelah izin MediaProjection diberikan. */
    fun startScreenLive(resultCode: Int, data: Intent, dests: List<Destination>): Result<Unit> = runCatching {
        val s = app.settings.settings.value
        releaseStream()
        startService(Intent(this, StreamService::class.java))
        goForeground(screen = true) // Android 14+: wajib sebelum getMediaProjection
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: error("Izin rekam layar ditolak")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (_state.value.streaming) stopLive() }
        }, null)
        if (AudioTester.running) stopAudioTest()
        // Selalu gunakan AxwareAudioSource agar pengaturan volume mikrofon & audio internal berfungsi
        // di semua mode (MIC, INTERNAL, maupun MIX), lengkap dengan meter level pasca-volume.
        val audio: AudioSource = if (Build.VERSION.SDK_INT >= 29) {
            AxwareAudioSource(s.audio, mp, s.micVolume, s.internalVolume)
        } else {
            AxwareAudioSource(AudioSourceKind.MIC, null, s.micVolume, s.internalVolume)
        }
        // Tangkapan mengikuti rasio layar HP, lalu ditata ke frame output oleh filter tata letak.
        val layoutFilter = GameLayoutFilterRender()
        val outW = s.outW; val outH = s.outH
        val source = GameScreenSource(applicationContext, mp, s.layout.orientation, maxOf(outW, outH)) { w, h ->
            captureSize = w to h
            layoutFilter.update(app.settings.settings.value.layout, outW, outH, w, h)
        }
        val st = buildStream(source, audio, s)
        // MediaProjection hanya menghasilkan frame saat layar berubah; paksa render agar fps konstan.
        st.getGlInterface().setForceRender(true, s.fps)
        st.getGlInterface().addFilter(layoutFilter)
        this.layoutFilter = layoutFilter
        stream = st
        activeSettings = s
        if (s.overlayInStream) {
            // Overlay ditumpuk langsung ke video; matikan overlay mengambang agar tidak dobel.
            if (FloatingOverlayService.running) runCatching {
                startService(Intent(this, FloatingOverlayService::class.java).setAction(FloatingOverlayService.ACTION_STOP))
            }
            startOverlayFilter(st, s)
        }
        val pairs = destPairs(dests)
        _state.value = LiveState(prepared = true, source = VideoSourceKind.SCREEN,
            destinations = dests.associate { it.id to DestStatus(it.name, DestState.CONNECTING) })
        st.startAll(pairs)
        if (s.recordLocal) startRecording()
        _state.value = _state.value.copy(streaming = true, startedAt = System.currentTimeMillis())
    }.onFailure { e -> _state.value = _state.value.copy(error = e.message); stopForegroundSafe() }

    fun stopLive() {
        val st = stream
        if (st != null) {
            if (st.isRecording) stopRecording()
            runCatching { st.stopAll() }
        }
        stopOverlayFilter()
        _state.value = _state.value.copy(streaming = false, startedAt = 0)
        if (_state.value.source == VideoSourceKind.SCREEN) {
            releaseStream()
            projection?.stop(); projection = null
            _state.value = _state.value.copy(prepared = false)
        }
        stopForegroundSafe()
    }

    // ------------------------------------------------------------------ tes audio
    private var testProjection: MediaProjection? = null

    /** Tes audio mic + internal tanpa siaran. Dipanggil setelah izin rekam layar diberikan. */
    fun startAudioTest(resultCode: Int, data: Intent): Result<Unit> = runCatching {
        check(!_state.value.streaming) { "Hentikan live dulu sebelum tes audio" }
        stopAudioTest()
        startService(Intent(this, StreamService::class.java))
        goForeground(screen = true)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: error("Izin rekam layar ditolak")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (AudioTester.running) AudioTester.stop() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        testProjection = mp
        val s = app.settings.settings.value
        val kind = if (Build.VERSION.SDK_INT >= 29) AudioSourceKind.MIX else AudioSourceKind.MIC
        AudioTester.start(kind, mp, s.micVolume, s.internalVolume)
    }.onFailure { stopAudioTest() }

    fun stopAudioTest() {
        AudioTester.stop()
        testProjection?.let { runCatching { it.stop() } }
        testProjection = null
        if (!_state.value.streaming) stopForegroundSafe()
    }

    fun stopDestination(id: String) { stream?.stopDestination(id) }

    fun switchCamera() {
        (stream?.videoSource as? Camera2Source)?.let { cam ->
            runCatching { cam.switchCamera() }
            app.settings.update { it.copy(frontCamera = cam.getCameraFacing() == CameraHelper.Facing.FRONT) }
            activeSettings = app.settings.settings.value
        }
    }

    fun toggleMic() {
        val a = stream?.audioSource ?: return
        val muted = when (a) {
            is MicrophoneSource -> { if (a.isMuted()) a.unMute() else a.mute(); a.isMuted() }
            is MixAudioSource -> { if (a.isMuted()) a.unMute() else a.mute(); a.isMuted() }
            is InternalAudioSource -> { if (a.isMuted()) a.unMute() else a.mute(); a.isMuted() }
            is AxwareAudioSource -> { a.muted = !a.muted; a.muted }
            else -> false
        }
        _state.value = _state.value.copy(micMuted = muted)
    }

    fun toggleLantern() {
        (stream?.videoSource as? Camera2Source)?.let { cam ->
            runCatching { if (cam.isLanternEnabled()) cam.disableLantern() else cam.enableLantern() }
        }
    }

    // ------------------------------------------------------------------ rekaman
    fun startRecording() {
        val st = stream ?: return
        if (st.isRecording) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        dir.mkdirs()
        val f = File(dir, "Axware_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        recordFile = f
        runCatching {
            st.startRecord(f.absolutePath, listener = object : RecordController.Listener {
                override fun onStatusChange(status: RecordController.Status) {}
            })
            _state.value = _state.value.copy(recording = true)
        }.onFailure { _state.value = _state.value.copy(error = "Rekam gagal: ${it.message}") }
    }

    fun stopRecording() {
        val st = stream ?: return
        if (!st.isRecording) return
        st.stopRecord()
        _state.value = _state.value.copy(recording = false)
        recordFile?.let { f -> Thread { publishToGallery(f) }.start() }
    }

    /** Salin hasil rekaman ke galeri (Movies/AxwareStream). */
    private fun publishToGallery(f: File) {
        if (!f.exists() || f.length() == 0L) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AxwareStream")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete()
            _state.value = _state.value.copy(lastRecording = "Movies/AxwareStream/${f.name}")
        }.onFailure { _state.value = _state.value.copy(lastRecording = f.absolutePath) }
    }

    // ------------------------------------------------------------------ overlay di video kamera
    private fun startOverlayFilter(st: MultiRtmpStream, s: StreamSettings) {
        stopOverlayFilter()
        val filter = ImageFilterRender()
        st.getGlInterface().addFilter(filter)
        filter.setScale(100f, 100f)
        filter.setPosition(0f, 0f)
        overlayFilter = filter
        overlayRunning = true
        val renderer = app.newRenderer()
        startWebLayers(renderer)
        // Resolusi bitmap overlay = 2/3 output untuk menghemat CPU, tetap tajam.
        val bw = (s.outW * 2 / 3).coerceAtLeast(360); val bh = (s.outH * 2 / 3).coerceAtLeast(360)
        val buffers = Array(3) { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }
        val listener: (com.zynnn.axwarestream.core.events.LiveEvent) -> Unit = { e -> renderer.onEvent(app.overlays.project.value.activeScene, e) }
        app.hub.addListener(listener)
        overlayThread = Thread({
            var i = 0
            try {
                while (overlayRunning) {
                    val scene = app.overlays.project.value.activeScene
                    val bmp = buffers[i % 3]; i++
                    renderer.renderToBitmap(bmp, scene, app.hub.stats.value, app.hub.recent(60))
                    filter.setImage(bmp)
                    Thread.sleep(150)
                }
            } catch (_: InterruptedException) {
            } finally {
                app.hub.removeListener(listener)
            }
        }, "overlay-filter").apply { isDaemon = true; start() }
    }

    /**
     * Elemen Web (Alert Sociabuzz) dijalankan di WebView tersembunyi lalu digambar ke bitmap overlay.
     * Bila izin "tampil di atas aplikasi lain" ada, WebView ditempel ke jendela tak terlihat (alpha 0)
     * agar dirender normal oleh sistem; tanpa izin, WebView dijalankan tanpa jendela.
     */
    private fun startWebLayers(renderer: com.zynnn.axwarestream.core.overlay.OverlayRenderer) {
        stopWebLayers()
        scope.launch {
            val container = android.widget.FrameLayout(this@StreamService)
            val host = com.zynnn.axwarestream.core.overlay.WebLayerHost(this@StreamService, container, captureSnapshots = true)
            if (android.provider.Settings.canDrawOverlays(this@StreamService)) runCatching {
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val lp = android.view.WindowManager.LayoutParams(1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START; alpha = 0f
                }
                wm.addView(container, lp)
                webWindow = lp
            }
            webLayers = host
            renderer.webSnapshot = { el -> host.snapshot(el) }
            app.overlays.project.collect { p ->
                val scene = p.activeScene
                val k = 2f / 3f
                host.sync(scene, k)
                webWindow?.let { lp ->
                    val w = (scene.width * k).toInt(); val h = (scene.height * k).toInt()
                    if (lp.width != w || lp.height != h) {
                        lp.width = w; lp.height = h
                        runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(container, lp) }
                    }
                }
            }
        }.also { webJob = it }
    }

    private fun stopWebLayers() {
        webJob?.cancel(); webJob = null
        val host = webLayers ?: return
        webLayers = null
        val c = host.container
        val release = {
            host.release()
            if (webWindow != null) runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(c) }
            webWindow = null
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) release() else android.os.Handler(android.os.Looper.getMainLooper()).post { release() }
    }

    private fun stopOverlayFilter() {
        stopWebLayers()
        overlayRunning = false
        overlayThread?.interrupt(); overlayThread = null
        overlayFilter?.let { f -> runCatching { stream?.getGlInterface()?.removeFilter(f) } }
        overlayFilter = null
    }

    private fun releaseStream() {
        stopOverlayFilter()
        stream?.let { st -> if (st.isOnPreview) runCatching { st.stopPreview(true) }; runCatching { st.release() } }
        stream = null
        layoutFilter = null
        captureSize = 0 to 0
    }

    private fun stopForegroundSafe() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        if (AudioTester.running) stopAudioTest()
        stopLive()
        releaseStream()
        projection?.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ callback RTMP
    override fun onDestinationState(id: String, state: DestState, detail: String) {
        val cur = _state.value.destinations[id] ?: DestStatus(destNames[id] ?: id, state)
        _state.value = _state.value.copy(destinations = _state.value.destinations + (id to cur.copy(state = state, detail = detail)))
    }

    override fun onDestinationBitrate(id: String, bps: Long) {
        val cur = _state.value.destinations[id] ?: return
        _state.value = _state.value.copy(destinations = _state.value.destinations + (id to cur.copy(kbps = bps / 1000)))
    }

    companion object {
        const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.zynnn.axwarestream.STOP"
    }
}
