package com.zynnn.axwarestream

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import com.zynnn.axwarestream.core.overlay.Templates
import com.zynnn.axwarestream.core.stream.Destination
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.AppRoot
import com.zynnn.axwarestream.ui.theme.AxwareTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private object FakeActions : AppActions {
    override fun withPermissions(perms: List<String>, then: () -> Unit) = then()
    override fun hasCameraPermission() = false
    override fun startScreenLive(dests: List<Destination>) {}
    override fun canDrawOverlays() = true
    override fun requestOverlayPermission() {}
    override fun setFloatingOverlay(on: Boolean) {}
    override fun pickImage(onResult: (String?) -> Unit) {}
    override fun importOverlay() {}
    override fun exportOverlay() {}
    override fun openUrl(url: String) {}
    override fun copy(label: String, text: String) {}
    override fun share(text: String) {}
    override fun toast(msg: String) {}
}

/** Render layar aplikasi sungguhan (Robolectric native graphics) ke PNG untuk diperiksa visual. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class UiScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun seed() {
        val app = AxwareApp.instance
        app.settings.updateTikTok { it.copy(username = "zynnn_173", server = "rtmp://push-rtmp-l11-sg01.tiktokcdn.com/game/",
            streamKey = "stream-123?expire=1", autoConnect = false) }
        app.hub.reset()
        listOf(
            LiveEvent(Platform.TIKTOK, EventType.CHAT, "zynnn_fan", "Halo bang, mantap live-nya"),
            LiveEvent(Platform.TIKTOK, EventType.GIFT, "Rina", "Rose", 5, "x5", diamonds = 5),
            LiveEvent(Platform.TIKTOK, EventType.CHAT, "Dimas", "GG kak"),
            LiveEvent(Platform.TIKTOK, EventType.GIFT, "Sultan Garut", "Finger Heart", 10, "x10", diamonds = 50),
            LiveEvent(Platform.TIKTOK, EventType.FOLLOW, "Sinta", "mengikuti kamu"),
            LiveEvent(Platform.TIKTOK, EventType.LIKE, "Ayu", "menyukai live", 15, "x15"),
            LiveEvent(Platform.TIKTOK, EventType.SHARE, "Bayu", "membagikan live"),
            LiveEvent(Platform.TIKTOK, EventType.CHAT, "Andi", "Main ranked dong bang, sekalian push rank mythic"),
        ).forEach { app.hub.emit(it) }
        app.hub.setLikeTotal(12840)
        app.hub.setViewers(Platform.TIKTOK, 1250)
    }

    private fun shot(tab: Int, name: String) {
        seed()
        compose.setContent { AxwareTheme { AppRoot(service = null, actions = FakeActions, startTab = tab) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun live() = shot(0, "01_live")
    @Config(qualifiers = "w400dp-h2600dp-xxhdpi")
    @Test fun liveTall() = tallShot(0, "01b_live_kamera_full") {
        AxwareApp.instance.settings.update { it.copy(source = com.zynnn.axwarestream.core.stream.VideoSourceKind.CAMERA) }
    }
    @Test fun interaksi() = shot(1, "02_interaksi")
    @Test fun overlay() = shot(2, "03_overlay")
    @Test fun setelan() = shot(3, "04_setelan")

    private fun tallShot(tab: Int, name: String, prep: () -> Unit) {
        seed(); prep()
        compose.setContent { AxwareTheme { AppRoot(service = null, actions = FakeActions, startTab = tab) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** Mode Layar/Game: editor tata letak 9:16, kontrol posisi, panel komentar live. */
    @Config(qualifiers = "w400dp-h2900dp-xxhdpi")
    @Test fun liveScreenLayout() = tallShot(0, "05_live_layar_916") {
        val app = AxwareApp.instance
        app.settings.update { it.copy(source = com.zynnn.axwarestream.core.stream.VideoSourceKind.SCREEN, portrait = true,
            layout = com.zynnn.axwarestream.core.stream.ScreenLayout(centerY = 0.3f, zoom = 1.1f)) }
    }

    @Config(qualifiers = "w400dp-h2900dp-xxhdpi")
    @Test fun liveScreenLayout169() = tallShot(0, "06_live_layar_169") {
        val app = AxwareApp.instance
        app.settings.update { it.copy(source = com.zynnn.axwarestream.core.stream.VideoSourceKind.SCREEN, portrait = false,
            layout = com.zynnn.axwarestream.core.stream.ScreenLayout(orientation = com.zynnn.axwarestream.core.stream.GameOrientation.PORTRAIT,
                background = com.zynnn.axwarestream.core.stream.LayoutBackground.COLOR, bgColor = 0xFF7C5CFF)) }
    }

    /** Setelan dengan sumber audio campuran, slider volume, dan kartu Tes audio. */
    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    @Test fun settingsAudio() = tallShot(3, "09_setelan_audio") {
        AxwareApp.instance.settings.update { it.copy(audio = com.zynnn.axwarestream.core.stream.AudioSourceKind.MIX, micVolume = 1.2f, internalVolume = 0.9f) }
    }

    /** Studio dengan elemen Alert Sociabuzz terpasang. */
    @Config(qualifiers = "w400dp-h2400dp-xxhdpi")
    @Test fun studioSociabuzz() = tallShot(2, "07_overlay_sociabuzz") {
        val repo = AxwareApp.instance.overlays
        val scene = repo.project.value.activeScene
        if (scene.elements.none { it.type == com.zynnn.axwarestream.core.overlay.ElementType.WEB })
            repo.addElement(scene.id, com.zynnn.axwarestream.core.overlay.OverlayElement.create(
                com.zynnn.axwarestream.core.overlay.ElementType.WEB, scene.width, scene.height)
                .copy(url = "https://sociabuzz.com/pro/tribe/alert1/v3/2003832666"))
    }

    @Test fun overlayTemplatesRender() {
        seed()
        val app = AxwareApp.instance
        val r = app.newRenderer()
        File("build/screenshots").mkdirs()
        Templates.builtIn().forEachIndexed { i, s ->
            val bmp = Bitmap.createBitmap(s.width / 2, s.height / 2, Bitmap.Config.ARGB_8888)
            r.renderToBitmap(bmp, s, app.hub.stats.value, app.hub.recent(60))
            // Pastikan benar-benar ada piksel yang tergambar (overlay tidak kosong).
            var drawn = 0
            for (y in 0 until bmp.height step 8) for (x in 0 until bmp.width step 8) if (bmp.getPixel(x, y) ushr 24 != 0) drawn++
            assertTrue("Template ${s.name} harus menggambar sesuatu", drawn > 50)
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawColor(0xFF203040.toInt()); drawBitmap(bmp, 0f, 0f, null) }
            File("build/screenshots/overlay_${i}_${s.name.replace(' ', '_')}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
