package com.zynnn.axwarestream.core.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Cara gambar mengisi kotak elemen (sama seperti CSS object-fit):
 * CONTAIN = gambar utuh, tidak terpotong (sisa ruang diisi warna latar);
 * COVER = kotak terisi penuh, kelebihan gambar terpotong; STRETCH = direntang mengikuti kotak.
 */
enum class ImageFitMode(val label: String) {
    CONTAIN("Utuh"), COVER("Isi penuh"), STRETCH("Regang");

    companion object {
        /** Nilai kosong (proyek lama) = CONTAIN agar gambar tidak lagi terpotong. */
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: CONTAIN
    }
}

object ImageFit {
    const val ZOOM_MIN = 0.2f
    const val ZOOM_MAX = 4f

    /**
     * Rumus bersama renderer native & overlay.html (Browser Source).
     * Menghasilkan [left, top, right, bottom] gambar di dalam kotak bw×bh (koordinat kotak, sebelum clip).
     * zoom mengalikan skala dasar mode; pan -1..1 = posisi (kiri/atas .. kanan/bawah), 0 = tengah,
     * sama dengan object-position 0%..100%.
     */
    fun rect(mode: ImageFitMode, bw: Float, bh: Float, iw: Float, ih: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): FloatArray {
        if (iw <= 0f || ih <= 0f || bw <= 0f || bh <= 0f) return floatArrayOf(0f, 0f, bw, bh)
        val z = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        val sx: Float; val sy: Float
        when (mode) {
            ImageFitMode.CONTAIN -> { val s = min(bw / iw, bh / ih) * z; sx = s; sy = s }
            ImageFitMode.COVER -> { val s = max(bw / iw, bh / ih) * z; sx = s; sy = s }
            ImageFitMode.STRETCH -> { sx = bw / iw * z; sy = bh / ih * z }
        }
        val dw = iw * sx; val dh = ih * sy
        val l = (bw - dw) * (panX.coerceIn(-1f, 1f) + 1f) / 2f
        val t = (bh - dh) * (panY.coerceIn(-1f, 1f) + 1f) / 2f
        return floatArrayOf(l, t, l + dw, t + dh)
    }

    /** Bagian gambar yang terpotong (0..1) — 0 berarti gambar tampil utuh. */
    fun croppedFraction(mode: ImageFitMode, bw: Float, bh: Float, iw: Float, ih: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): Float {
        val r = rect(mode, bw, bh, iw, ih, zoom, panX, panY)
        val dw = r[2] - r[0]; val dh = r[3] - r[1]
        if (dw <= 0 || dh <= 0) return 0f
        val vw = (min(r[2], bw) - max(r[0], 0f)).coerceAtLeast(0f)
        val vh = (min(r[3], bh) - max(r[1], 0f)).coerceAtLeast(0f)
        return (1f - (vw * vh) / (dw * dh)).coerceIn(0f, 1f)
    }
}

/** Pemuat gambar overlay: menghormati rotasi EXIF (foto kamera) dan mengecilkan gambar besar agar hemat memori. */
object ImageStore {
    const val MAX_SIDE = 2048

    private fun exifRotation(f: File): Pair<Int, Boolean> = runCatching {
        when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
            ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
            ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
            ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
            ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
            else -> 0 to false
        }
    }.getOrDefault(0 to false)

    /** Ukuran tampil gambar (lebar, tinggi) setelah rotasi EXIF, tanpa memuat piksel. */
    fun size(f: File): Pair<Int, Int>? {
        if (!f.isFile) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val rot = exifRotation(f).first
        return if (rot == 90 || rot == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    fun sampleSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        if (max(w, h) / s > maxSide * 2) s *= 2
        return s
    }

    fun load(f: File, maxSide: Int = MAX_SIDE): Bitmap? {
        if (!f.isFile) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(b.outWidth, b.outHeight, maxSide) }
        val raw = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        val (rot, flip) = exifRotation(f)
        if (rot == 0 && !flip) return raw
        val m = Matrix().apply { if (flip) postScale(-1f, 1f); if (rot != 0) postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }
}

/**
 * Sesuaikan kotak elemen dengan rasio gambar (gambar pas, tanpa sisa ruang & tanpa terpotong).
 * Lebar dipertahankan dan titik tengah tetap; bila hasilnya lebih besar dari [maxW]×[maxH] diperkecil proporsional.
 */
fun OverlayElement.fitBoxToImage(iw: Int, ih: Int, maxW: Float, maxH: Float): OverlayElement {
    if (iw <= 0 || ih <= 0) return this
    val ratio = iw.toFloat() / ih
    var nw = w
    var nh = nw / ratio
    val k = minOf(1f, maxW / nw, maxH / nh)
    nw *= k; nh *= k
    val cx = x + w / 2; val cy = y + h / 2
    return copy(x = cx - nw / 2, y = cy - nh / 2, w = nw, h = nh)
}

/** Ubah lebar; bila rasio dikunci, tinggi ikut berubah sesuai rasio gambar. */
fun OverlayElement.withWidth(nw: Float, ratio: Float?): OverlayElement =
    if (ratio != null && ratio > 0f && lockRatio) copy(w = nw, h = nw / ratio) else copy(w = nw)

/** Ubah tinggi; bila rasio dikunci, lebar ikut berubah sesuai rasio gambar. */
fun OverlayElement.withHeight(nh: Float, ratio: Float?): OverlayElement =
    if (ratio != null && ratio > 0f && lockRatio) copy(h = nh, w = nh * ratio) else copy(h = nh)
