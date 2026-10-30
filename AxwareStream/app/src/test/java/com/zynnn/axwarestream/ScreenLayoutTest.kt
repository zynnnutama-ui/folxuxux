package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.overlay.ElementType
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.stream.FitMode
import com.zynnn.axwarestream.core.stream.GameOrientation
import com.zynnn.axwarestream.core.stream.ScreenLayout
import com.zynnn.axwarestream.ui.components.previewCaptureSize
import com.zynnn.axwarestream.ui.components.withPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji matematika tata letak layar game di frame 9:16 / 16:9 (murni JVM). */
class ScreenLayoutTest {
    private val eps = 1e-4f

    @Test fun captureSizeKeepsPhoneAspect() {
        // HP 1080x2400 (20:9). Landscape -> 1280x576, portrait -> 576x1280. Selalu genap.
        assertEquals(1280 to 576, ScreenLayout.captureSize(1080, 2400, GameOrientation.LANDSCAPE, 1280))
        assertEquals(576 to 1280, ScreenLayout.captureSize(1080, 2400, GameOrientation.PORTRAIT, 1280))
        assertEquals(576 to 1280, ScreenLayout.captureSize(1080, 2400, GameOrientation.AUTO, 1280))
        assertEquals(1280 to 576, ScreenLayout.captureSize(2400, 1080, GameOrientation.AUTO, 1280))
        val (w, h) = ScreenLayout.captureSize(1080, 2340, GameOrientation.LANDSCAPE, 1920)
        assertEquals(0, w % 2); assertEquals(0, h % 2); assertEquals(1920, w)
        assertEquals(1280 to 576, previewCaptureSize(1080, 2400, GameOrientation.AUTO))
    }

    @Test fun fitLandscapeGameInPortraitFrame() {
        val r = ScreenLayout().contentRect(720, 1280, 1280, 576)
        // Lebar penuh, tinggi = (9/16)/(1280/576) = 0.253125, di tengah.
        assertEquals(0f, r.x, eps); assertEquals(1f, r.w, eps)
        assertEquals(0.253125f, r.h, eps); assertEquals(0.5f - 0.253125f / 2, r.y, eps)
    }

    @Test fun fillCropsSides() {
        val r = ScreenLayout(fit = FitMode.FILL).contentRect(720, 1280, 1280, 576)
        assertEquals(1f, r.h, eps); assertEquals(1f / 0.253125f, r.w, 1e-3f)
        assertEquals(0.5f, r.x + r.w / 2, eps)
    }

    @Test fun zoomAndMove() {
        val l = ScreenLayout().withZoom(2f).moved(0.1f, -0.2f)
        val r = l.contentRect(720, 1280, 1280, 576)
        assertEquals(2f, r.w, eps); assertEquals(0.50625f, r.h, eps)
        assertEquals(0.6f, r.x + r.w / 2, eps); assertEquals(0.3f, r.y + r.h / 2, eps)
        assertEquals(ScreenLayout.MAX_ZOOM, ScreenLayout().withZoom(99f).zoom, eps)
        assertEquals(ScreenLayout.MIN_ZOOM, ScreenLayout().withZoom(0f).zoom, eps)
    }

    @Test fun presetsTopBottom() {
        val top = ScreenLayout().withPreset("TOP", 720, 1280, 1280, 576).contentRect(720, 1280, 1280, 576)
        assertEquals(0f, top.y, eps)
        val bottom = ScreenLayout().withPreset("BOTTOM", 720, 1280, 1280, 576).contentRect(720, 1280, 1280, 576)
        assertEquals(1f, bottom.bottom, eps)
        // Game portrait di frame portrait: memenuhi frame, preset tidak menggeser.
        val full = ScreenLayout().withPreset("TOP", 720, 1280, 576, 1280)
        assertEquals(0.5f, full.centerY, eps)
    }

    @Test fun portraitGameInLandscapeFrame() {
        val r = ScreenLayout().contentRect(1280, 720, 576, 1280)
        assertEquals(1f, r.h, eps)
        assertEquals((16f / 9f).let { 1f / (it / (576f / 1280f)) }, r.w, eps)
    }

    @Test fun coverRectFillsFrame() {
        val c = ScreenLayout().coverRect(720, 1280, 1280, 576)
        assertEquals(1f, c.h, eps); assertTrue(c.w > 1f); assertEquals(0.5f, c.x + c.w / 2, eps)
    }

    @Test fun webElementJson() {
        val el = OverlayElement.create(ElementType.WEB, 1080, 1920)
            .copy(url = "https://sociabuzz.com/pro/tribe/alert1/v3/123?x=1&y=2", pageScale = 1.25f)
        val back = OverlayElement.fromJson(JSONObject(el.toJson().toString()))
        assertEquals(el, back)
        assertEquals(ElementType.WEB, back.type)
        assertTrue(ElementType.WEB.dynamic)
        // Proyek lama tanpa field url tetap terbaca.
        val old = OverlayElement.fromJson(JSONObject("""{"id":"a","type":"TEXT","text":"hai"}"""))
        assertEquals("", old.url); assertEquals(1f, old.pageScale, eps)
    }
}
