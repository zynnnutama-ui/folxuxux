package com.zynnn.axwarestream.core.stream

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Orientasi tangkapan layar game. AUTO mengikuti rotasi HP saat live. */
enum class GameOrientation(val label: String) {
    AUTO("Otomatis"), LANDSCAPE("Landscape"), PORTRAIT("Portrait");
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: AUTO }
}

/** FIT = seluruh layar game terlihat (ada ruang kosong), FILL = memenuhi frame (sisi game terpotong). */
enum class FitMode(val label: String) {
    FIT("Pas (utuh)"), FILL("Penuh (crop)");
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: FIT }
}

enum class LayoutBackground(val label: String) {
    BLUR("Blur game"), BLACK("Hitam"), COLOR("Warna");
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: BLUR }
}

/** Kotak ternormalisasi (0..1) dengan titik asal kiri-atas frame output. */
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}

/**
 * Tata letak layar game di dalam frame siaran (mis. game landscape di dalam frame 9:16).
 * zoom 1 = ukuran dasar sesuai [fit]; centerX/centerY = posisi titik tengah game (0..1).
 */
data class ScreenLayout(
    val orientation: GameOrientation = GameOrientation.AUTO,
    val fit: FitMode = FitMode.FIT,
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val background: LayoutBackground = LayoutBackground.BLUR,
    val bgColor: Long = 0xFF101018,
) {
    /**
     * Hitung kotak game di frame output.
     * @param outW/outH ukuran output (mis. 720x1280)
     * @param srcW/srcH ukuran tangkapan game (mis. 1280x576)
     */
    fun contentRect(outW: Int, outH: Int, srcW: Int, srcH: Int): NRect {
        val outA = outW.toFloat() / outH
        val srcA = srcW.toFloat() / srcH
        // Lebar & tinggi ternormalisasi saat game memenuhi lebar frame.
        var w = 1f
        var h = outA / srcA
        val fitScale = if (fit == FitMode.FIT) min(1f / w, 1f / h) else max(1f / w, 1f / h)
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        w *= fitScale * z; h *= fitScale * z
        return NRect(centerX - w / 2f, centerY - h / 2f, w, h)
    }

    /** Kotak latar (cover penuh frame, terpusat) untuk mode blur. */
    fun coverRect(outW: Int, outH: Int, srcW: Int, srcH: Int): NRect {
        val outA = outW.toFloat() / outH
        val srcA = srcW.toFloat() / srcH
        var w = 1f; var h = outA / srcA
        val s = max(1f / w, 1f / h)
        w *= s; h *= s
        return NRect(0.5f - w / 2f, 0.5f - h / 2f, w, h)
    }

    fun withZoom(z: Float) = copy(zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM))
    fun moved(dx: Float, dy: Float) = copy(centerX = (centerX + dx).coerceIn(-0.5f, 1.5f), centerY = (centerY + dy).coerceIn(-0.5f, 1.5f))

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3f

        /** Ukuran tangkapan layar: rasio mengikuti layar HP (tanpa bar hitam), sisi panjang = [longSide]. */
        fun captureSize(displayW: Int, displayH: Int, orientation: GameOrientation, longSide: Int): Pair<Int, Int> {
            val dl = max(displayW, displayH).toFloat(); val ds = min(displayW, displayH).toFloat()
            val landscape = when (orientation) {
                GameOrientation.LANDSCAPE -> true
                GameOrientation.PORTRAIT -> false
                GameOrientation.AUTO -> displayW >= displayH
            }
            val short = even((longSide * ds / dl).roundToInt())
            val long = even(longSide)
            return if (landscape) long to short else short to long
        }

        private fun even(v: Int) = max(2, v - (v % 2))
    }
}
