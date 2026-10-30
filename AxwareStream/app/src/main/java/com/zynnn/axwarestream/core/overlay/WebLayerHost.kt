package com.zynnn.axwarestream.core.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.ConcurrentHashMap

/**
 * Menampilkan elemen overlay bertipe WEB (mis. Alert Sociabuzz) memakai WebView sungguhan.
 * Halaman Sociabuzz memakai JavaScript + Ably realtime, jadi harus dijalankan di mesin browser,
 * bukan digambar ulang. Kelas ini dipakai di dua tempat:
 *  - Jendela overlay mengambang: WebView tampil langsung di atas layar.
 *  - Siaran kamera/layar (RTMP): WebView digambar ke Bitmap ([snapshot]) lalu ditumpuk ke video.
 * Semua method harus dipanggil di main thread, kecuali [snapshot] (aman dari thread mana pun).
 */
class WebLayerHost(private val context: Context, val container: FrameLayout, private val captureSnapshots: Boolean) {

    private class Layer(val web: WebView, var url: String, var scale: Float) {
        val buffers = arrayOfNulls<Bitmap>(3)
        var idx = 0
        @Volatile var front: Bitmap? = null
    }

    private val layers = HashMap<String, Layer>()
    private val fronts = ConcurrentHashMap<String, Bitmap>()
    private val main = Handler(Looper.getMainLooper())
    private var capturing = false

    private val captureTick = object : Runnable {
        override fun run() { if (!capturing) return; capture(); main.postDelayed(this, 120) }
    }

    /**
     * Sinkronkan WebView dengan elemen WEB di [scene].
     * @param k skala unit scene -> piksel container. @param offX/offY offset letterbox dalam piksel.
     */
    fun sync(scene: OverlayScene, k: Float, offX: Float = 0f, offY: Float = 0f) {
        val webs = scene.elements.filter { it.type == ElementType.WEB && it.visible && isValidUrl(it.url) }
        val ids = webs.map { it.id }.toSet()
        layers.keys.filter { it !in ids }.forEach { id -> layers.remove(id)?.let { destroy(id, it) } }
        for (el in webs) {
            val w = (el.w * k).toInt().coerceAtLeast(2); val h = (el.h * k).toInt().coerceAtLeast(2)
            // Halaman dirender seperti Browser Source selebar (el.w / 1,5) px CSS lalu diperbesar.
            val scale = (1.5f * el.pageScale.coerceIn(0.25f, 4f) * k)
            val layer = layers[el.id] ?: Layer(newWebView(), "", 0f).also { layers[el.id] = it; container.addView(it.web) }
            val lp = (layer.web.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(w, h)
            lp.width = w; lp.height = h
            lp.leftMargin = (offX + el.x * k).toInt(); lp.topMargin = (offY + el.y * k).toInt()
            layer.web.layoutParams = lp
            layer.web.alpha = el.opacity.coerceIn(0f, 1f)
            layer.web.rotation = el.rotation
            if (layer.scale != scale) { layer.scale = scale; layer.web.setInitialScale((scale * 100).toInt().coerceAtLeast(1)) }
            if (layer.url != el.url) { layer.url = el.url; layer.web.loadUrl(el.url) }
        }
        if (container.parent == null) layoutDetached()
        if (captureSnapshots && layers.isNotEmpty() && !capturing) { capturing = true; main.post(captureTick) }
        if (layers.isEmpty()) capturing = false
    }

    /** Muat ulang semua halaman (mis. setelah koneksi putus). */
    fun reload() { layers.values.forEach { it.web.reload() } }

    val count: Int get() = layers.size

    /** Snapshot terbaru elemen WEB (null bila belum ada). Aman dipanggil dari thread render overlay. */
    fun snapshot(el: OverlayElement): Bitmap? = fronts[el.id]

    private fun layoutDetached() {
        // Tanpa jendela (izin overlay tidak ada): ukur & tata letak manual agar WebView tetap punya ukuran.
        val w = layers.values.maxOfOrNull { (it.web.layoutParams as FrameLayout.LayoutParams).let { lp -> lp.leftMargin + lp.width } } ?: 1
        val h = layers.values.maxOfOrNull { (it.web.layoutParams as FrameLayout.LayoutParams).let { lp -> lp.topMargin + lp.height } } ?: 1
        container.measure(View.MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h.coerceAtLeast(1), View.MeasureSpec.EXACTLY))
        container.layout(0, 0, w, h)
    }

    private fun capture() {
        if (container.parent == null) layoutDetached()
        for ((id, l) in layers) {
            val w = l.web.width; val h = l.web.height
            if (w <= 0 || h <= 0) continue
            var b = l.buffers[l.idx]
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); l.buffers[l.idx] = b }
            b.eraseColor(Color.TRANSPARENT)
            runCatching { l.web.draw(Canvas(b)) }
            fronts[id] = b
            l.front = b
            l.idx = (l.idx + 1) % l.buffers.size
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        // Software layer agar bisa digambar ke Bitmap untuk ditumpuk ke video.
        if (captureSnapshots) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = false
        settings.setSupportZoom(false)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun destroy(id: String, l: Layer) {
        fronts.remove(id)
        container.removeView(l.web)
        runCatching { l.web.stopLoading(); l.web.loadUrl("about:blank"); l.web.destroy() }
    }

    fun release() {
        capturing = false
        main.removeCallbacks(captureTick)
        layers.forEach { (id, l) -> destroy(id, l) }
        layers.clear()
    }

    companion object {
        fun isValidUrl(u: String): Boolean {
            val t = u.trim()
            if (!(t.startsWith("https://") || t.startsWith("http://"))) return false
            val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            return host.contains('.') && host.length >= 4 && !t.contains(' ')
        }

        fun isSociabuzz(u: String) = isValidUrl(u) &&
            u.substringAfter("://").substringBefore('/').let { it == "sociabuzz.com" || it.endsWith(".sociabuzz.com") }
    }
}
