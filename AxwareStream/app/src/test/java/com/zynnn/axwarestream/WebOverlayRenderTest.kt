package com.zynnn.axwarestream

import android.graphics.Bitmap
import android.graphics.Color
import com.zynnn.axwarestream.core.events.LiveStats
import com.zynnn.axwarestream.core.overlay.ElementType
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.core.overlay.WebLayerHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Elemen Web/Alert Sociabuzz: penanda hanya di editor, snapshot halaman tergambar di output video. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WebOverlayRenderTest {
    private val el = OverlayElement.create(ElementType.WEB, 1080, 1920).copy(id = "web1", url = "https://sociabuzz.com/pro/tribe/alert1/v3/1")
    private val scene = OverlayScene(elements = listOf(el))

    private fun render(placeholder: Boolean, snap: Bitmap?): Bitmap {
        val r = AxwareApp.instance.newRenderer()
        r.webPlaceholder = placeholder
        r.webSnapshot = if (snap != null) { e -> if (e.id == "web1") snap else null } else null
        val bmp = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        r.renderToBitmap(bmp, scene, LiveStats(), emptyList())
        return bmp
    }

    private fun opaqueCount(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) if (b.getPixel(x, y) ushr 24 != 0) n++
        return n
    }

    @Test fun outputHasNoPlaceholder() {
        assertEquals("Output siaran tanpa snapshot harus transparan", 0, opaqueCount(render(false, null)))
    }

    @Test fun editorShowsPlaceholder() {
        assertTrue(opaqueCount(render(true, null)) > 100)
    }

    @Test fun snapshotDrawnAtElementRect() {
        val snap = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val out = render(false, snap)
        val cx = ((el.x + el.w / 2) / 2).toInt(); val cy = ((el.y + el.h / 2) / 2).toInt()
        assertEquals(Color.RED, out.getPixel(cx, cy))
        // Di luar kotak elemen tetap transparan.
        assertEquals(0, out.getPixel(5, 5) ushr 24)
        assertEquals(0, out.getPixel(cx, ((el.y + el.h) / 2 + 40).toInt()) ushr 24)
    }

    @Test fun urlValidation() {
        assertTrue(WebLayerHost.isValidUrl("https://sociabuzz.com/pro/tribe/alert1/v3/2003832666?a=1"))
        assertTrue(WebLayerHost.isSociabuzz("https://sociabuzz.com/pro/tribe/alert1/v3/2003832666"))
        assertTrue(WebLayerHost.isSociabuzz("https://www.sociabuzz.com/x"))
        assertFalse(WebLayerHost.isSociabuzz("https://sociabuzz.com.evil.io/x"))
        assertTrue(WebLayerHost.isValidUrl("https://saweria.co/widgets/alert?streamKey=abc"))
        assertFalse(WebLayerHost.isValidUrl("sociabuzz.com/abc"))
        assertFalse(WebLayerHost.isValidUrl("https://socia buzz.com"))
        assertFalse(WebLayerHost.isValidUrl("javascript:alert(1)"))
        assertFalse(WebLayerHost.isValidUrl(""))
    }
}
