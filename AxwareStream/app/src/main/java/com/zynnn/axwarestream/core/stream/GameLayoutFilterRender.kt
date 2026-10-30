package com.zynnn.axwarestream.core.stream

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Filter GL yang menata ulang tangkapan layar game di dalam frame siaran:
 * game ditempatkan di kotak [contentRect] (posisi & zoom bebas), sisa frame diisi latar
 * blur dari game, hitam, atau warna. Input = frame tangkapan yang sudah direntang ke ukuran output.
 *
 * Blur halus (mode BLUR) dibuat bertahap di GPU:
 *  1. Downsample: latar (game diperbesar menutupi frame) dirender ke tekstur kecil 1/[DOWNSCALE]
 *     dengan rata-rata 4x4 titik per piksel (anti-aliasing, tidak berkedip saat game bergerak).
 *  2. Blur Gaussian terpisah (horizontal lalu vertikal) di tekstur kecil, diulang [BLUR_PASSES] kali.
 *  3. Komposit: area game = tangkapan asli, area kosong = tekstur blur yang diperbesar (bilinear).
 * Biaya kecil karena blur dikerjakan di resolusi 1/8.
 */
class GameLayoutFilterRender : BaseFilterRender() {

    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 0f, 0f,
        1f, -1f, 0f, 1f, 0f,
        -1f, 1f, 0f, 0f, 1f,
        1f, 1f, 0f, 1f, 1f,
    )

    // Program komposit (akhir).
    private var program = -1
    private var aPosition = -1
    private var aTexCoord = -1
    private var uMVP = -1
    private var uST = -1
    private var uSampler = -1
    private var uBlurTex = -1
    private var uRect = -1
    private var uBgMode = -1
    private var uBgColor = -1
    private var uDim = -1

    // Program downsample.
    private var dProgram = -1
    private var dPos = -1
    private var dTex = -1
    private var dSampler = -1
    private var dCover = -1
    private var dStep = -1

    // Program blur Gaussian satu arah.
    private var gProgram = -1
    private var gPos = -1
    private var gTex = -1
    private var gSampler = -1
    private var gDir = -1

    /** 2 FBO kecil (ping-pong) untuk blur. */
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var smallW = 0
    private var smallH = 0

    @Volatile private var rect = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile private var cover = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile private var bgMode = 0
    @Volatile private var bgColor = floatArrayOf(0f, 0f, 0f)

    init {
        squareVertex = ByteBuffer.allocateDirect(vertexData.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        squareVertex.put(vertexData).position(0)
        Matrix.setIdentityM(MVPMatrix, 0)
        Matrix.setIdentityM(STMatrix, 0)
    }

    /** Perbarui tata letak (boleh dipanggil dari thread mana pun, berlaku di frame berikutnya). */
    fun update(layout: ScreenLayout, outW: Int, outH: Int, srcW: Int, srcH: Int) {
        val r = layout.contentRect(outW, outH, srcW, srcH)
        val c = layout.coverRect(outW, outH, srcW, srcH)
        rect = floatArrayOf(r.x, r.y, r.w, r.h)
        cover = floatArrayOf(c.x, c.y, c.w, c.h)
        bgMode = when (layout.background) { LayoutBackground.BLUR -> 1; LayoutBackground.BLACK -> 0; LayoutBackground.COLOR -> 2 }
        val col = layout.bgColor
        bgColor = floatArrayOf(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f)
    }

    override fun initGlFilter(context: Context) {
        if (smallW > 0) release() // inisialisasi ulang: bebaskan sumber daya lama dulu
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uST = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        uBlurTex = GLES20.glGetUniformLocation(program, "uBlurTex")
        uRect = GLES20.glGetUniformLocation(program, "uRect")
        uBgMode = GLES20.glGetUniformLocation(program, "uBgMode")
        uBgColor = GLES20.glGetUniformLocation(program, "uBgColor")
        uDim = GLES20.glGetUniformLocation(program, "uDim")

        dProgram = GlUtil.createProgram(PASS_VERTEX, DOWNSAMPLE_FRAGMENT)
        dPos = GLES20.glGetAttribLocation(dProgram, "aPosition")
        dTex = GLES20.glGetAttribLocation(dProgram, "aTextureCoord")
        dSampler = GLES20.glGetUniformLocation(dProgram, "uSampler")
        dCover = GLES20.glGetUniformLocation(dProgram, "uCover")
        dStep = GLES20.glGetUniformLocation(dProgram, "uStep")

        gProgram = GlUtil.createProgram(PASS_VERTEX, GAUSS_FRAGMENT)
        gPos = GLES20.glGetAttribLocation(gProgram, "aPosition")
        gTex = GLES20.glGetAttribLocation(gProgram, "aTextureCoord")
        gSampler = GLES20.glGetUniformLocation(gProgram, "uSampler")
        gDir = GLES20.glGetUniformLocation(gProgram, "uDir")

        val (sw, sh) = smallSize(width, height)
        smallW = sw; smallH = sh
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, fboTex, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sw, sh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GlUtil.checkGlError("GameLayout blur init")
    }

    private fun bindQuad(pos: Int, tex: Int) {
        squareVertex.position(SQUARE_VERTEX_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(pos)
        squareVertex.position(SQUARE_VERTEX_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, SQUARE_VERTEX_DATA_STRIDE_BYTES, squareVertex)
        GLES20.glEnableVertexAttribArray(tex)
    }

    /** Langkah 1-2: hasilkan latar blur halus ke fboTex[0]. */
    private fun renderBlur() {
        val c = cover
        GLES20.glViewport(0, 0, smallW, smallH)
        // Downsample: input -> fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glUseProgram(dProgram)
        bindQuad(dPos, dTex)
        GLES20.glUniform4f(dCover, c[0], c[1], c[2], c[3])
        GLES20.glUniform2f(dStep, 1f / smallW, 1f / smallH)
        GLES20.glUniform1i(dSampler, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.disableResources(dTex, dPos)
        // Gaussian H (0 -> 1) lalu V (1 -> 0), diulang.
        GLES20.glUseProgram(gProgram)
        bindQuad(gPos, gTex)
        GLES20.glUniform1i(gSampler, 0)
        repeat(BLUR_PASSES) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
            GLES20.glUniform2f(gDir, 1f / smallW, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[1])
            GLES20.glUniform2f(gDir, 0f, 1f / smallH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GlUtil.disableResources(gTex, gPos)
        // Kembalikan target ke FBO milik filter (BaseFilterRender.draw akan menggambar ke sini).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, getRenderHandler().fboId[0])
        GLES20.glViewport(0, 0, width, height)
    }

    override fun drawFilter() {
        val r = rect; val col = bgColor; val mode = bgMode
        val needBlur = mode == 1 && !coversFrame(r) && smallW > 0
        if (needBlur) renderBlur()

        GLES20.glUseProgram(program)
        bindQuad(aPosition, aTexCoord)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, MVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uST, 1, false, STMatrix, 0)
        GLES20.glUniform4f(uRect, r[0], r[1], r[2], r[3])
        GLES20.glUniform1f(uBgMode, if (mode == 1 && !needBlur) 0f else mode.toFloat())
        GLES20.glUniform3f(uBgColor, col[0], col[1], col[2])
        GLES20.glUniform1f(uDim, BLUR_DIM)
        GLES20.glUniform1i(uSampler, 0)
        GLES20.glUniform1i(uBlurTex, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() {
        GlUtil.disableResources(aTexCoord, aPosition)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(dProgram)
        GLES20.glDeleteProgram(gProgram)
        GLES20.glDeleteFramebuffers(2, fbo, 0)
        GLES20.glDeleteTextures(2, fboTex, 0)
        smallW = 0; smallH = 0
    }

    companion object {
        /** Faktor perkecilan tekstur blur (1/8 resolusi output). */
        const val DOWNSCALE = 8
        /** Jumlah putaran blur Gaussian (H+V). Lebih banyak = lebih halus/lembut. */
        const val BLUR_PASSES = 2
        /** Kecerahan latar blur (0..1) agar game tetap menonjol. */
        const val BLUR_DIM = 0.45f

        /** Ukuran tekstur blur kecil untuk output [w]x[h]. */
        fun smallSize(w: Int, h: Int): Pair<Int, Int> =
            (w / DOWNSCALE).coerceAtLeast(16) to (h / DOWNSCALE).coerceAtLeast(16)

        /** True bila kotak game menutupi seluruh frame (latar tidak terlihat, blur dilewati). */
        fun coversFrame(r: FloatArray): Boolean = r[0] <= 0f && r[1] <= 0f && r[0] + r[2] >= 1f && r[1] + r[3] >= 1f

        const val VERTEX = """attribute vec4 aPosition;
attribute vec4 aTextureCoord;
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}"""

        /** Vertex untuk pass blur (tanpa matriks). */
        const val PASS_VERTEX = """attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
  gl_Position = aPosition;
  vTextureCoord = aTextureCoord.xy;
}"""

        /**
         * Downsample: tiap piksel kecil = rata-rata 4x4 titik (bilinear) dari latar cover.
         * Koordinat tekstur mengikuti konvensi FBO GL (baris 0 = bawah), sama dengan frame output.
         */
        const val DOWNSAMPLE_FRAGMENT = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D uSampler;
uniform vec4 uCover;
uniform vec2 uStep;
vec4 sampleTop(vec2 q) { return texture2D(uSampler, vec2(q.x, 1.0 - q.y)); }
void main() {
  vec2 p = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 4; i++) {
    for (int j = 0; j < 4; j++) {
      vec2 o = (vec2(float(i), float(j)) + 0.5) / 4.0 - 0.5;
      vec2 b = clamp((p + o * uStep - uCover.xy) / uCover.zw, 0.0, 1.0);
      acc += sampleTop(b);
    }
  }
  gl_FragColor = vec4(acc.rgb / 16.0, 1.0);
}"""

        /** Blur Gaussian satu arah, 15 tap (sigma 3 piksel kecil), bobot dinormalisasi. */
        const val GAUSS_FRAGMENT = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D uSampler;
uniform vec2 uDir;
void main() {
  vec3 acc = vec3(0.0);
  float ws = 0.0;
  for (int i = -7; i <= 7; i++) {
    float x = float(i);
    float w = exp(-x * x / 18.0);
    acc += w * texture2D(uSampler, vTextureCoord + uDir * x).rgb;
    ws += w;
  }
  gl_FragColor = vec4(acc / ws, 1.0);
}"""

        /** GLSL ES 1.00 (sama untuk OpenGL ES 2 & WebGL 1, diuji di Chromium lewat test). */
        const val FRAGMENT = """precision mediump float;
varying vec2 vTextureCoord;
uniform sampler2D uSampler;
uniform sampler2D uBlurTex;
uniform vec4 uRect;
uniform float uBgMode;
uniform vec3 uBgColor;
uniform float uDim;
vec4 sampleTop(vec2 q) { return texture2D(uSampler, vec2(q.x, 1.0 - q.y)); }
void main() {
  vec2 p = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);
  vec2 q = (p - uRect.xy) / uRect.zw;
  if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
    gl_FragColor = sampleTop(q);
    return;
  }
  if (uBgMode > 0.5 && uBgMode < 1.5) {
    vec3 c = texture2D(uBlurTex, vec2(p.x, 1.0 - p.y)).rgb;
    gl_FragColor = vec4(c * uDim, 1.0);
  } else if (uBgMode > 1.5) {
    gl_FragColor = vec4(uBgColor, 1.0);
  } else {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  }
}"""
    }
}
