package com.zynnn.axwarestream.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Mengukur kecepatan upload nyata dengan mengirim data acak ke speed.cloudflare.com/__up.
 * Hasil dipakai untuk mengecek apakah bitrate siaran TikTok muat di uplink sebelum live.
 */
object SpeedTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @return kecepatan upload dalam kbps. */
    fun measureUploadKbps(bytes: Int = 6 * 1024 * 1024): Int {
        val chunk = ByteArray(64 * 1024).also { Random.nextBytes(it) }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes.toLong()
            override fun writeTo(sink: BufferedSink) {
                var left = bytes
                while (left > 0) { val n = minOf(left, chunk.size); sink.write(chunk, 0, n); left -= n }
            }
        }
        val req = Request.Builder().url("https://speed.cloudflare.com/__up").post(body).build()
        val start = System.nanoTime()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.close()
        }
        val sec = (System.nanoTime() - start) / 1e9
        return ((bytes * 8 / 1000.0) / sec).toInt()
    }
}
