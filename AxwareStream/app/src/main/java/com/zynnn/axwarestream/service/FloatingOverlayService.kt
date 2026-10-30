package com.zynnn.axwarestream.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.MainActivity
import com.zynnn.axwarestream.R
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.overlay.OverlayRenderer
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.core.overlay.WebLayerHost
import kotlin.math.abs
import kotlin.math.min

class FloatingOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlayView: OverlayCanvasView? = null
    private var root: FrameLayout? = null
    private var webHost: WebLayerHost? = null
    private var bubble: BubbleView? = null
    private val app get() = application as AxwareApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        if (overlayView == null) show()
        running = true
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, AxwareApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Overlay Axware aktif")
            .setContentText("Overlay tampil di atas layar. Ketuk bubble A untuk sembunyikan/tampilkan.")
            .setOngoing(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Tutup overlay", stop)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ov = OverlayCanvasView(this, app.newRenderer())
        val container = FrameLayout(this)
        container.addView(ov, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val host = WebLayerHost(this, container, captureSnapshots = false)
        ov.onLayoutChanged = { scene, k, ox, oy -> host.sync(scene, k, ox, oy) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 31) lp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(container, lp)
        overlayView = ov; root = container; webHost = host

        val b = BubbleView(this)
        val size = (48 * resources.displayMetrics.density).toInt()
        val blp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 24; y = 300
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = blp.x; startY = blp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    blp.x = startX + dx.toInt(); blp.y = startY + dy.toInt()
                    wm.updateViewLayout(b, blp)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    container.visibility = if (container.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                    b.dimmed = container.visibility != View.VISIBLE; b.invalidate()
                }
            }
            true
        }
        wm.addView(b, blp)
        bubble = b
    }

    override fun onDestroy() {
        running = false
        overlayView?.let { runCatching { it.release() } }
        webHost?.let { runCatching { it.release() } }
        root?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        overlayView = null; bubble = null; root = null; webHost = null
        super.onDestroy()
    }

    private class OverlayCanvasView(ctx: Context, private val renderer: OverlayRenderer) : View(ctx) {
        private val app = ctx.applicationContext as AxwareApp
        private val listener: (LiveEvent) -> Unit = { e -> renderer.onEvent(app.overlays.project.value.activeScene, e) }
        var onLayoutChanged: ((OverlayScene, Float, Float, Float) -> Unit)? = null
        private var lastKey: Any? = null
        private val ticker = object : Runnable {
            override fun run() { checkLayout(); invalidate(); postDelayed(this, 66) }
        }

        private fun checkLayout() {
            if (width == 0 || height == 0) return
            val scene = app.overlays.project.value.activeScene
            val key = Triple(scene, width, height)
            if (key == lastKey) return
            lastKey = key
            val s = min(width.toFloat() / scene.width, height.toFloat() / scene.height)
            onLayoutChanged?.invoke(scene, s, (width - scene.width * s) / 2f, (height - scene.height * s) / 2f)
        }
        init { app.hub.addListener(listener); post(ticker) }

        fun release() { removeCallbacks(ticker); app.hub.removeListener(listener) }

        override fun onDraw(canvas: Canvas) {
            val scene = app.overlays.project.value.activeScene
            val s = min(width.toFloat() / scene.width, height.toFloat() / scene.height)
            canvas.save()
            canvas.translate((width - scene.width * s) / 2f, (height - scene.height * s) / 2f)
            canvas.scale(s, s)
            renderer.draw(canvas, scene, app.hub.stats.value, app.hub.recent(60))
            canvas.restore()
        }
    }

    private class BubbleView(ctx: Context) : View(ctx) {
        var dimmed = false
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2D55.toInt() }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            bg.alpha = if (dimmed) 110 else 235
            canvas.drawCircle(r, r, r, bg)
            fg.textSize = r * 1.1f
            canvas.drawText("A", r, r - (fg.ascent() + fg.descent()) / 2, fg)
        }
    }

    companion object {
        const val NOTIF_ID = 4202
        const val ACTION_STOP = "com.zynnn.axwarestream.OVERLAY_STOP"
        @Volatile var running = false
            private set
    }
}
