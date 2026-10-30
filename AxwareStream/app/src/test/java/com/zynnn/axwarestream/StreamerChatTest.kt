package com.zynnn.axwarestream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import com.zynnn.axwarestream.service.StreamerChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

/** Jendela komentar mengambang: daftar bisa digulir, posisi tidak melompat saat membaca komentar lama. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
class StreamerChatTest {
    private inline fun <reified T> field(o: Any, name: String): T =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T

    private fun layout(root: View) {
        val w = (250 * root.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun commentsScroll() {
        ShadowSettings.setCanDrawOverlays(true)
        AxwareApp.instance.getSharedPreferences("streamer_chat", 0).edit().clear().commit()
        val app = AxwareApp.instance
        app.hub.reset()
        repeat(30) { app.hub.emit(LiveEvent(Platform.TIKTOK, EventType.CHAT, "user$it", "komentar ke-$it dari penonton")) }
        val ctrl = Robolectric.buildService(StreamerChatService::class.java).create().startCommand(0, 1)
        val svc = ctrl.get()
        shadowOf(Looper.getMainLooper()).idle()
        val root: LinearLayout = field(svc, "root")
        val list: LinearLayout = field(svc, "list")
        val sv: ScrollView = field(svc, "scroll")
        val chip: TextView = field(svc, "newChip")
        layout(root)
        assertEquals("riwayat komentar dimuat", 30, list.childCount)
        assertTrue("konten lebih tinggi dari jendela -> bisa digulir", list.height > sv.height)
        // Awal: otomatis di paling bawah (komentar terbaru).
        sv.scrollTo(0, list.height - sv.height); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sv.canScrollVertically(-1)); assertTrue(!sv.canScrollVertically(1))

        // Pengguna menggulir ke atas untuk membaca komentar lama.
        sv.scrollTo(0, 0); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sv.canScrollVertically(1))
        app.hub.emit(LiveEvent(Platform.TIKTOK, EventType.CHAT, "baru", "komentar baru masuk"))
        shadowOf(Looper.getMainLooper()).idle(); layout(root)
        assertEquals("posisi baca tidak melompat", 0, sv.scrollY)
        assertEquals(View.VISIBLE, chip.visibility)
        assertEquals("↓ 1 baru", chip.text.toString())

        // Ketuk penanda -> lompat ke komentar terbaru.
        chip.performClick(); shadowOf(Looper.getMainLooper()).idle(); layout(root)
        sv.scrollTo(0, list.height - sv.height)
        assertEquals(View.GONE, chip.visibility)
        assertEquals(31, list.childCount)

        // Simpan gambar jendela untuk diperiksa.
        sv.scrollTo(0, 0); layout(root)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/08_jendela_komentar.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ctrl.destroy()
    }

    private fun texts(list: LinearLayout) = (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }

    @Test fun joinFollowShareRepostShown() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = AxwareApp.instance
        app.getSharedPreferences("streamer_chat", 0).edit().clear().commit()
        app.hub.reset()
        val ctrl = Robolectric.buildService(StreamerChatService::class.java).create().startCommand(0, 1)
        val svc = ctrl.get()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        idle()
        val root: LinearLayout = field(svc, "root")
        val list: LinearLayout = field(svc, "list")
        fun emit(t: EventType, u: String, m: String = "") { app.hub.emit(LiveEvent(Platform.TIKTOK, t, u, m)); idle() }

        emit(EventType.CHAT, "Dimas", "Halo bang")
        emit(EventType.JOIN, "Sinta", "bergabung")
        emit(EventType.JOIN, "Bayu", "bergabung")
        emit(EventType.JOIN, "Rina", "bergabung")
        emit(EventType.FOLLOW, "Ayu", "mengikuti kamu")
        emit(EventType.SHARE, "Joko", "membagikan live")
        emit(EventType.REPOST, "Putri", "memposting ulang live")
        emit(EventType.JOIN, "Andi", "bergabung")
        emit(EventType.CHAT, "Rina", "GG kak")
        val t = texts(list)
        println("Isi jendela:\n" + t.joinToString("\n"))
        assertEquals(listOf(
            "Dimas  Halo bang",
            "MasukRina, Bayu dan 1 lainnya bergabung",
            "FollowAyu  mengikuti kamu",
            "ShareJoko  membagikan live",
            "RepostPutri  memposting ulang live",
            "MasukAndi bergabung",
            "Rina  GG kak",
        ), t)

        // Matikan filter "Masuk" & "Repost": event jenis itu tidak ditampilkan lagi, komentar tetap tampil.
        val filters: LinearLayout = field(svc, "filters")
        fun chip(key: String) = (0 until filters.childCount).map { filters.getChildAt(it) }.first { it.tag == key }
        chip("join").performClick(); chip("repost").performClick(); idle()
        assertTrue(!svc.filterOn("join")); assertTrue(!svc.filterOn("repost")); assertTrue(svc.filterOn("follow"))
        emit(EventType.JOIN, "Tono", "bergabung")
        emit(EventType.REPOST, "Tini", "memposting ulang live")
        emit(EventType.FOLLOW, "Budi", "mengikuti kamu")
        emit(EventType.CHAT, "Tono", "baru masuk kak")
        val t2 = texts(list).drop(t.size)
        assertEquals(listOf("FollowBudi  mengikuti kamu", "Tono  baru masuk kak"), t2)
        chip("join").performClick(); chip("repost").performClick(); idle()
        assertTrue(svc.filterOn("join") && svc.filterOn("repost"))

        layout(root)
        val sv: ScrollView = field(svc, "scroll")
        sv.scrollTo(0, 0); layout(root)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/10_jendela_aktivitas.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ctrl.destroy()
    }

    /** Kontrol perbesar/perkecil ada di BAWAH jendela (bukan di judul), tombol × dihapus, berfungsi, dan tersimpan. */
    @Test fun resizeControlsAtBottom() {
        ShadowSettings.setCanDrawOverlays(true)
        val app = AxwareApp.instance
        app.getSharedPreferences("streamer_chat", 0).edit().clear().commit()
        app.hub.reset()
        repeat(20) { app.hub.emit(LiveEvent(Platform.TIKTOK, EventType.CHAT, "user$it", "komentar ke-$it")) }
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        var ctrl = Robolectric.buildService(StreamerChatService::class.java).create().startCommand(0, 1)
        var svc = ctrl.get(); idle()
        val root: LinearLayout = field(svc, "root")
        val d = root.resources.displayMetrics.density
        layout(root)

        // Judul tidak lagi berisi tombol ukuran maupun tombol × (hanya bisa ditutup lewat aplikasi).
        val header = root.getChildAt(0) as LinearLayout
        val headerTexts = (0 until header.childCount).map { (header.getChildAt(it) as TextView).text.toString() }
        println("Tombol judul: $headerTexts")
        assertTrue("tombol ⇕ tidak ada di judul", headerTexts.none { it.contains("⇕") })
        assertTrue("tombol × tidak ada di judul", headerTexts.none { it == "×" || it.equals("x", ignoreCase = true) })
        assertEquals(listOf("● Komentar live", "–"), headerTexts)

        // Bilah ukuran = anak terakhir, berada di bawah daftar komentar.
        val listFrame: View = field(svc, "listFrameView")
        val footer = root.getChildAt(root.childCount - 1) as LinearLayout
        assertTrue(footer === field<LinearLayout>(svc, "footerView"))
        assertTrue("bilah ukuran di bawah daftar", footer.top >= listFrame.bottom)
        assertTrue("bilah ukuran di dasar jendela", root.height - footer.bottom <= (10 * d).toInt())
        fun byTag(tag: String) = (0 until footer.childCount).map { footer.getChildAt(it) }.first { it.tag == tag }
        val shrink = byTag("shrink") as TextView
        val grow = byTag("grow") as TextView
        val grip = byTag("grip")
        assertEquals("−", shrink.text.toString()); assertEquals("+", grow.text.toString())
        val label: TextView = field(svc, "sizeLabel")

        fun h() = listFrame.layoutParams.height
        assertEquals((240 * d).toInt(), h()); assertEquals("Ukuran 240 dp", label.text.toString())
        grow.performClick(); idle(); layout(root)
        assertEquals((280 * d).toInt(), h()); assertEquals((280 * d).toInt(), listFrame.height)
        shrink.performClick(); shrink.performClick(); idle(); layout(root)
        assertEquals((200 * d).toInt(), h())
        // Batas bawah: tombol perkecil nonaktif.
        repeat(10) { shrink.performClick() }; idle(); layout(root)
        assertEquals((StreamerChatService.MIN_HEIGHT_DP * d).toInt(), h())
        assertTrue(!shrink.isEnabled); assertTrue(grow.isEnabled)
        // Batas atas: 70% layar (800dp -> 520dp maks konstanta, 560 -> 520).
        repeat(20) { grow.performClick() }; idle(); layout(root)
        val maxDp = minOf(StreamerChatService.MAX_HEIGHT_DP, 800 * 0.7f)
        assertEquals((maxDp * d).toInt(), h()); assertTrue(!grow.isEnabled)

        // Seret pegangan ke atas 120dp -> lebih kecil 120dp.
        fun touch(a: Int, y: Float) = android.view.MotionEvent.obtain(0, 0, a, 10f, y, 0).also { it.setLocation(10f, y) }
        val y0 = 500f
        grip.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_DOWN, y0))
        // rawY = y (tanpa offset di Robolectric)
        grip.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_MOVE, y0 - 120 * d))
        grip.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_UP, y0 - 120 * d)); idle(); layout(root)
        assertEquals(((maxDp - 120) * d).toInt(), h())

        // Gambar jendela setelah diatur.
        svc.setListHeight(200f); idle(); layout(root)
        val sv: ScrollView = field(svc, "scroll"); sv.scrollTo(0, 0); layout(root)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screenshots").mkdirs()
        File("build/screenshots/13_jendela_ukuran_bawah.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Minimize menyembunyikan bilah ukuran, buka lagi menampilkannya.
        val toggle = header.getChildAt(1) as TextView
        toggle.performClick(); idle(); assertEquals(View.GONE, footer.visibility)
        toggle.performClick(); idle(); assertEquals(View.VISIBLE, footer.visibility)
        ctrl.destroy()

        // Ukuran tersimpan: jendela dibuka ulang memakai tinggi terakhir.
        ctrl = Robolectric.buildService(StreamerChatService::class.java).create().startCommand(0, 1)
        svc = ctrl.get(); idle()
        val lf2: View = field(svc, "listFrameView")
        assertEquals((200 * d).toInt(), lf2.layoutParams.height)
        ctrl.destroy()
    }
}
