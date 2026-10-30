package com.zynnn.axwarestream

import com.zynnn.axwarestream.core.stream.GameLayoutFilterRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Latar blur halus untuk area kosong di sekitar game.
 * Shader asli (semua pass) dijalankan di Chromium WebGL lewat tools/shader-test/test_shader.py
 * (GLSL ES 1.00 sama dengan OpenGL ES 2 di Android) dan dibandingkan dengan blur lama.
 */
class GameBlurShaderTest {

    @Test fun smallTextureAndSkip() {
        assertEquals(90 to 160, GameLayoutFilterRender.smallSize(720, 1280))
        assertEquals(160 to 90, GameLayoutFilterRender.smallSize(1280, 720))
        assertEquals(16 to 16, GameLayoutFilterRender.smallSize(40, 40))
        // Game memenuhi frame (mode Penuh) -> pass blur dilewati.
        assertTrue(GameLayoutFilterRender.coversFrame(floatArrayOf(-0.5f, 0f, 2f, 1f)))
        assertFalse(GameLayoutFilterRender.coversFrame(floatArrayOf(0f, 0.37f, 1f, 0.25f)))
        // Shader memakai semua uniform yang di-set dari Kotlin.
        for (u in listOf("uBlurTex", "uRect", "uBgMode", "uBgColor", "uDim")) assertTrue(u, GameLayoutFilterRender.FRAGMENT.contains(u))
        assertTrue(GameLayoutFilterRender.DOWNSAMPLE_FRAGMENT.contains("uCover") && GameLayoutFilterRender.DOWNSAMPLE_FRAGMENT.contains("uStep"))
        assertTrue(GameLayoutFilterRender.GAUSS_FRAGMENT.contains("uDir"))
    }

    @Test fun smoothBlurInWebGl() {
        val script = File("../tools/shader-test/test_shader.py").absoluteFile
        val chrome = File(System.getProperty("user.home"), ".cache/ms-playwright").walkTopDown().maxDepth(3)
            .firstOrNull { it.name == "chrome" && it.isFile }
        assumeTrue("Chromium/Playwright tidak tersedia", chrome != null && script.isFile)
        val p = ProcessBuilder("python3", script.path).directory(script.parentFile).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        assertTrue("selesai", p.waitFor(180, TimeUnit.SECONDS))
        println(log)
        assertEquals(log, 0, p.exitValue())
        assertTrue(log.contains("HASIL: LULUS"))
        File("build/screenshots").mkdirs()
        File(script.parentFile, "blur_banding.png").copyTo(File("build/screenshots/14_blur_halus.png"), overwrite = true)
    }
}
