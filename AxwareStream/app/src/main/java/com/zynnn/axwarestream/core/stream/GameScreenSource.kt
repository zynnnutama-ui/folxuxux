package com.zynnn.axwarestream.core.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.pedro.encoder.input.sources.MediaProjectionHandler
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * Sumber video layar untuk mode game. Berbeda dengan ScreenSource bawaan RootEncoder,
 * ukuran tangkapan mengikuti rasio layar HP (bukan rasio output), sehingga game landscape
 * tidak di-letterbox oleh sistem. Penataan ke frame output (9:16 / 16:9) dilakukan oleh
 * [GameLayoutFilterRender]. Dengan [GameOrientation.AUTO], tangkapan ikut berputar saat HP diputar.
 */
class GameScreenSource(
    context: Context,
    mediaProjection: MediaProjection,
    private val orientation: GameOrientation,
    private val longSide: Int,
    /** Dipanggil setiap ukuran tangkapan berubah (lebar, tinggi). */
    private val onCaptureSize: (Int, Int) -> Unit,
) : VideoSource() {

    private val appContext = context.applicationContext
    private val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val dpi = context.resources.displayMetrics.densityDpi
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var thread: HandlerThread? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var captureW = 0; private set
    @Volatile var captureH = 0; private set

    init { MediaProjectionHandler.mediaProjection = mediaProjection }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) applySize() }
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        require(width % 2 == 0 && height % 2 == 0) { "width and height values must be divisible by 2" }
        return true
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = android.graphics.Point()
        d?.getRealSize(p)
        return if (p.x > 0 && p.y > 0) p.x to p.y else appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun computeSize(): Pair<Int, Int> {
        val (dw, dh) = displaySize()
        return ScreenLayout.captureSize(dw, dh, orientation, longSide)
    }

    private fun applySize() {
        val (w, h) = computeSize()
        if (w == captureW && h == captureH) return
        captureW = w; captureH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
        virtualDisplay?.resize(w, h, dpi)
        onCaptureSize(w, h)
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return
        val (w, h) = computeSize()
        captureW = w; captureH = h
        surfaceTexture.setDefaultBufferSize(w, h)
        val t = HandlerThread("GameScreenSource").also { it.start() }
        val s = Surface(surfaceTexture)
        try {
            virtualDisplay = MediaProjectionHandler.mediaProjection?.createVirtualDisplay(
                "AxwareGame", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, Handler(t.looper),
            ) ?: throw IllegalArgumentException("Gagal membuat virtual display")
        } catch (e: Exception) {
            s.release(); t.quitSafely(); throw e
        }
        surface = s; thread = t
        onCaptureSize(w, h)
        if (orientation == GameOrientation.AUTO) dm.registerDisplayListener(displayListener, main)
    }

    override fun stop() {
        runCatching { dm.unregisterDisplayListener(displayListener) }
        virtualDisplay?.release(); virtualDisplay = null
        surface?.release(); surface = null
        thread?.quitSafely(); thread = null
    }

    override fun release() {}

    override fun isRunning(): Boolean = virtualDisplay != null

    /** Frame sudah tegak di ruang output: tanpa rotasi kamera. */
    override fun getOrientationConfig(): OrientationConfig {
        val isPortrait = (rotation == 90 || rotation == 270) != (height > width)
        return OrientationConfig(cameraOrientation = 0, isPortrait = isPortrait, forced = OrientationForced.NONE)
    }
}
