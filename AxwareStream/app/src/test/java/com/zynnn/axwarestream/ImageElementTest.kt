package com.zynnn.axwarestream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import com.zynnn.axwarestream.core.events.EventHub
import com.zynnn.axwarestream.core.events.LiveStats
import com.zynnn.axwarestream.core.overlay.ElementType
import com.zynnn.axwarestream.core.overlay.ImageFit
import com.zynnn.axwarestream.core.overlay.ImageFitMode
import com.zynnn.axwarestream.core.overlay.ImageStore
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.OverlayProject
import com.zynnn.axwarestream.core.overlay.OverlayRenderer
import com.zynnn.axwarestream.core.overlay.OverlayRepository
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.core.overlay.fitBoxToImage
import com.zynnn.axwarestream.core.overlay.withHeight
import com.zynnn.axwarestream.core.overlay.withWidth
import com.zynnn.axwarestream.core.server.OverlayServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Elemen Gambar: tidak terpotong (mode Utuh), bisa disesuaikan, dan sama antara renderer Android & Browser Source. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageElementTest {
    private val dir = Files.createTempDirectory("axwimg").toFile()
    private val media = File(dir, "media").apply { mkdirs() }

    /** Gambar uji: tepi kiri MERAH, tepi kanan BIRU, tengah HIJAU, strip atas KUNING (untuk mendeteksi pemotongan & cermin). */
    private fun testImage(name: String, w: Int, h: Int): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint()
        c.drawColor(Color.GREEN)
        p.color = Color.RED; c.drawRect(0f, 0f, w * 0.1f, h.toFloat(), p)
        p.color = Color.BLUE; c.drawRect(w * 0.9f, 0f, w.toFloat(), h.toFloat(), p)
        p.color = Color.YELLOW; c.drawRect(w * 0.1f, 0f, w * 0.9f, h * 0.1f, p)
        File(media, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    private fun render(vararg els: OverlayElement, w: Int = 540, h: Int = 960): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        OverlayRenderer(media).renderToBitmap(out, OverlayScene(elements = els.toList()), LiveStats(), emptyList())
        return out
    }

    private fun count(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, pred: (Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (pred(b.getPixel(x, y))) n++
        return n
    }
    private fun near(c: Int, t: Int) = Color.alpha(c) > 200 && abs(Color.red(c) - Color.red(t)) < 60 && abs(Color.green(c) - Color.green(t)) < 60 && abs(Color.blue(c) - Color.blue(t)) < 60

    // ---------- Rumus ----------

    @Test fun containNeverCrops() {
        for ((iw, ih) in listOf(800 to 200, 200 to 800, 500 to 500, 1920 to 1080, 1080 to 1920, 37 to 911))
            for ((bw, bh) in listOf(400f to 400f, 900f to 120f, 120f to 900f, 1080f to 1920f)) {
                val r = ImageFit.rect(ImageFitMode.CONTAIN, bw, bh, iw.toFloat(), ih.toFloat())
                assertTrue("gambar ${iw}x$ih di kotak ${bw}x$bh: $r", r[0] >= -0.01f && r[1] >= -0.01f && r[2] <= bw + 0.01f && r[3] <= bh + 0.01f)
                assertEquals(0f, ImageFit.croppedFraction(ImageFitMode.CONTAIN, bw, bh, iw.toFloat(), ih.toFloat()), 1e-4f)
                // Rasio gambar tetap.
                assertEquals(iw.toFloat() / ih, (r[2] - r[0]) / (r[3] - r[1]), 0.01f)
                // Salah satu sisi menyentuh kotak (sebesar mungkin).
                assertTrue(abs((r[2] - r[0]) - bw) < 0.01f || abs((r[3] - r[1]) - bh) < 0.01f)
            }
        // Gambar lebar 4:1 di kotak 400x400 mode lama (COVER) terpotong 75%.
        assertEquals(0.75f, ImageFit.croppedFraction(ImageFitMode.COVER, 400f, 400f, 800f, 200f), 1e-3f)
    }

    @Test fun zoomPanStretch() {
        // Pan -1 = tepi kiri gambar di tepi kiri kotak; pan 1 = tepi kanan di kanan.
        val l = ImageFit.rect(ImageFitMode.COVER, 400f, 400f, 800f, 200f, 1f, -1f, 0f)
        assertEquals(0f, l[0], 1e-3f)
        val r = ImageFit.rect(ImageFitMode.COVER, 400f, 400f, 800f, 200f, 1f, 1f, 0f)
        assertEquals(400f, r[2], 1e-3f)
        // Zoom 50% di mode Utuh: gambar setengah ukuran, tetap di tengah.
        val z = ImageFit.rect(ImageFitMode.CONTAIN, 400f, 400f, 800f, 200f, 0.5f)
        assertEquals(200f, z[2] - z[0], 1e-3f); assertEquals(400f, (z[0] + z[2]), 1e-3f)
        // Zoom dibatasi 0,2..4.
        assertEquals(ImageFit.rect(ImageFitMode.CONTAIN, 400f, 400f, 800f, 200f, 4f)[2], ImageFit.rect(ImageFitMode.CONTAIN, 400f, 400f, 800f, 200f, 99f)[2], 1e-3f)
        // Regang = tepat seukuran kotak.
        assertTrue(ImageFit.rect(ImageFitMode.STRETCH, 300f, 500f, 800f, 200f).contentEquals(floatArrayOf(0f, 0f, 300f, 500f)))
    }

    @Test fun boxFollowsImageAndJson() {
        val el = OverlayElement.create(ElementType.IMAGE, 1080, 1920)
        assertEquals(ImageFitMode.CONTAIN, el.fit)
        val f = el.fitBoxToImage(1600, 900, 1080f, 1920f)
        assertEquals(1600f / 900f, f.w / f.h, 1e-3f)
        assertEquals(el.x + el.w / 2, f.x + f.w / 2, 1e-3f); assertEquals(el.y + el.h / 2, f.y + f.h / 2, 1e-3f)
        // Gambar sangat tinggi tetap muat di kanvas.
        val tall = el.copy(w = 1000f).fitBoxToImage(500, 5000, 1080f, 1920f)
        assertTrue(tall.h <= 1920.01f); assertEquals(0.1f, tall.w / tall.h, 1e-3f)
        // Kunci rasio.
        val lk = f.withWidth(800f, 16f / 9f); assertEquals(450f, lk.h, 1e-2f)
        assertEquals(320f, f.withHeight(180f, 16f / 9f).w, 1e-2f)
        assertEquals(f.h, f.copy(lockRatio = false).withWidth(800f, 16f / 9f).h, 1e-3f)
        // JSON: field baru tersimpan; proyek lama (tanpa "fit") dibaca sebagai CONTAIN agar tidak terpotong.
        val e2 = el.copy(fit = ImageFitMode.COVER, imgZoom = 1.5f, imgPanX = -0.4f, imgPanY = 0.7f, flipH = true, lockRatio = false)
        assertEquals(e2, OverlayElement.fromJson(e2.toJson()))
        val old = JSONObject("""{"id":"a","type":"IMAGE","media":"x.png","w":400,"h":400}""")
        val o = OverlayElement.fromJson(old)
        assertEquals(ImageFitMode.CONTAIN, o.fit); assertEquals(1f, o.imgZoom); assertTrue(o.lockRatio)
    }

    // ---------- Render native ----------

    @Test fun nativeContainShowsWholeImage() {
        val img = testImage("lebar.png", 800, 200)
        // Kotak persegi 400x400 di kanvas 1080x1920 (render ke 540x960 = skala 0,5).
        val el = OverlayElement(type = ElementType.IMAGE, x = 100f, y = 200f, w = 400f, h = 400f, cornerRadius = 0f, media = img)
        val out = render(el)
        File("build/screenshots").mkdirs()
        // Kotak di output: x 50..250, y 100..300. Gambar utuh 400x100 => y 175..225 (output).
        assertTrue("tepi kiri merah tampil", count(out, 50, 150, 60, 250) { near(it, Color.RED) } > 200)
        assertTrue("tepi kanan biru tampil", count(out, 240, 150, 250, 250) { near(it, Color.BLUE) } > 200)
        assertTrue("strip kuning atas tampil", count(out, 70, 170, 230, 182) { near(it, Color.YELLOW) } > 500)
        // Sisa ruang atas/bawah transparan (latar kosong).
        assertEquals(0, count(out, 50, 100, 250, 170) { Color.alpha(it) > 0 })
        assertEquals(0, count(out, 50, 230, 250, 300) { Color.alpha(it) > 0 })

        // Mode lama (COVER) memotong tepi kiri/kanan — terbukti masalahnya.
        val cover = render(el.copy(fit = ImageFitMode.COVER))
        assertEquals(0, count(cover, 50, 100, 250, 300) { near(it, Color.RED) || near(it, Color.BLUE) })
        // COVER + geser horizontal -1: tepi kiri (merah) kembali terlihat.
        val pan = render(el.copy(fit = ImageFitMode.COVER, imgPanX = -1f))
        assertTrue(count(pan, 50, 100, 60, 300) { near(it, Color.RED) } > 1000)
        // Cermin: biru pindah ke kiri.
        val flip = render(el.copy(flipH = true))
        assertTrue(count(flip, 50, 150, 60, 250) { near(it, Color.BLUE) } > 200)
        // Latar terisi di sisa ruang mode Utuh.
        val bg = render(el.copy(bgColor = 0xFF000000))
        assertTrue(count(bg, 50, 100, 250, 170) { Color.alpha(it) == 255 && Color.red(it) < 20 && Color.green(it) < 20 } > 10000)
        // Simpan perbandingan.
        val sheet = Bitmap.createBitmap(1080, 300, Bitmap.Config.ARGB_8888); val sc = Canvas(sheet); sc.drawColor(0xFF22222A.toInt())
        listOf(out, cover, pan, flip).forEachIndexed { i, b -> sc.drawBitmap(Bitmap.createBitmap(b, 40, 90, 220, 220), 20f + i * 265f, 40f, null) }
        File("build/screenshots/11_gambar_mode.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun exifRotationAndLargeImages() {
        // Foto kamera: piksel tersimpan landscape 400x200 + tag EXIF "putar 90°" => tampil portrait 200x400.
        val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        Canvas(b).drawRect(0f, 0f, 40f, 200f, Paint().apply { color = Color.RED }) // tepi kiri (sebelum diputar)
        val f = File(media, "foto.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        assertEquals(200 to 400, ImageStore.size(f))
        val bm = ImageStore.load(f)!!
        assertEquals(200, bm.width); assertEquals(400, bm.height)
        // Putar 90° searah jarum jam: tepi kiri asli menjadi tepi atas.
        assertTrue(near(bm.getPixel(100, 10), Color.RED)); assertTrue(near(bm.getPixel(100, 390), Color.GREEN))
        // Gambar besar diperkecil (hemat memori) tetapi rasio tetap.
        assertEquals(1, ImageStore.sampleSize(2000, 1000)); assertEquals(2, ImageStore.sampleSize(6000, 3000)); assertEquals(4, ImageStore.sampleSize(12000, 9000))
        val big = Bitmap.createBitmap(5000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bf = File(media, "besar.png"); bf.outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val lb = ImageStore.load(bf)!!
        assertTrue(lb.width <= 4096); assertEquals(5f, lb.width.toFloat() / lb.height, 0.01f)
        assertEquals(5000 to 1000, ImageStore.size(bf))
    }

    // ---------- Browser Source (Chromium sungguhan) vs renderer Android ----------

    @Test fun browserSourceMatchesNative() {
        val chrome = File(System.getProperty("user.home"), ".cache/ms-playwright").walkTopDown().maxDepth(3).firstOrNull { it.name == "chrome" && it.canExecute() }
        val script = File("../tools/overlay-image-test/shot.py").absoluteFile
        assumeTrue("Chromium/Playwright tidak tersedia", chrome != null && script.isFile)
        val wide = testImage("lebar.png", 800, 200)
        val tall = testImage("tinggi.png", 200, 800)
        val els = listOf(
            OverlayElement(id = "a", type = ElementType.IMAGE, x = 40f, y = 60f, w = 480f, h = 480f, cornerRadius = 0f, media = wide),
            OverlayElement(id = "b", type = ElementType.IMAGE, x = 560f, y = 60f, w = 480f, h = 480f, cornerRadius = 0f, media = tall, bgColor = 0xFF202020),
            OverlayElement(id = "c", type = ElementType.IMAGE, x = 40f, y = 600f, w = 480f, h = 480f, cornerRadius = 0f, media = wide, fit = ImageFitMode.COVER, imgPanX = -1f),
            OverlayElement(id = "d", type = ElementType.IMAGE, x = 560f, y = 600f, w = 480f, h = 300f, cornerRadius = 0f, media = tall, fit = ImageFitMode.STRETCH),
            OverlayElement(id = "e", type = ElementType.IMAGE, x = 40f, y = 1140f, w = 480f, h = 480f, cornerRadius = 0f, media = wide, imgZoom = 1.6f, imgPanX = 0.5f, flipH = true),
            OverlayElement(id = "f", type = ElementType.IMAGE, x = 560f, y = 1140f, w = 480f, h = 480f, cornerRadius = 0f, media = tall, fit = ImageFitMode.COVER, imgZoom = 0.6f, imgPanY = 1f),
        )
        val scene = OverlayScene(id = "s1", elements = els)
        val repo = OverlayRepository(File(dir, "p.json"), media)
        repo.update { OverlayProject(scenes = listOf(scene), activeSceneId = "s1") }
        val server = OverlayServer(EventHub(), repo, webhookToken = "")
        val port = server.start(0)
        val png = File(dir, "browser.png")
        try {
            val p = ProcessBuilder("python3", script.path, "http://127.0.0.1:$port/overlay", "540", "960", png.path, chrome!!.path)
                .redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            assertTrue("Chromium selesai", p.waitFor(60, TimeUnit.SECONDS)); println(log)
            assertEquals(log, 0, p.exitValue())
        } finally { server.stop() }
        val web = BitmapFactory.decodeFile(png.path)
        val nat = render(*els.toTypedArray())
        assertEquals(540, web.width); assertEquals(960, web.height)
        var diff = 0; var total = 0
        for (y in 0 until 960) for (x in 0 until 540) {
            val a = web.getPixel(x, y); val b = nat.getPixel(x, y)
            if (Color.alpha(a) == 0 && Color.alpha(b) == 0) continue
            total++
            val d = abs(Color.alpha(a) - Color.alpha(b)) + abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            if (d > 80) diff++
        }
        val pct = diff * 100.0 / total
        println("Browser vs native: piksel berisi=$total, beda=$diff (${"%.2f".format(pct)}%)")
        File("build/screenshots").mkdirs()
        png.copyTo(File("build/screenshots/12_gambar_browser.png"), overwrite = true)
        File("build/screenshots/12_gambar_native.png").outputStream().use { nat.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(total > 100_000)
        assertTrue("Browser Source harus sama dengan renderer Android (beda ${"%.2f".format(pct)}%)", pct < 1.5)
    }
}
