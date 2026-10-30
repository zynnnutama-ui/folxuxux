package com.zynnn.axwarestream.core.stream

/**
 * Tujuan siaran TikTok LIVE (RTMP). Server URL & Stream Key didapat dari TikTok LIVE Producer
 * (https://livecenter.tiktok.com/producer) dan berlaku untuk satu sesi siaran.
 */
data class Destination(
    val id: String = "tiktok",
    val name: String = "TikTok LIVE",
    val server: String = "",
    val streamKey: String = "",
) {
    /** URL lengkap RTMP: server + "/" + key (jika key diisi terpisah). */
    fun fullUrl(): String {
        val s = server.trim()
        val k = streamKey.trim()
        if (k.isEmpty()) return s
        return if (s.endsWith("/")) s + k else "$s/$k"
    }

    fun validate(): String? {
        val s = server.trim()
        if (s.isEmpty()) return "Server URL TikTok belum diisi"
        if (!(s.startsWith("rtmp://") || s.startsWith("rtmps://"))) return "Server URL harus diawali rtmp:// atau rtmps://"
        if (s.length < 12) return "Server URL belum lengkap"
        if (streamKey.isBlank() && s.substringAfter("://").count { it == '/' } < 2) return "Stream key TikTok belum diisi"
        if (streamKey.any { it.isWhitespace() }) return "Stream key mengandung spasi. Salin ulang dari LIVE Producer."
        return null
    }

    companion object {
        /**
         * Pisahkan URL lengkap yang ditempel sekaligus (server/kunci) menjadi server + key.
         * Contoh: rtmp://push.example.com/game/stream-123?expire=..&sign=.. -> (rtmp://push.example.com/game/, stream-123?expire=..&sign=..)
         */
        fun splitFullUrl(full: String): Pair<String, String>? {
            val t = full.trim()
            if (!(t.startsWith("rtmp://") || t.startsWith("rtmps://"))) return null
            val afterHost = t.substringAfter("://").substringAfter('/', "")
            val segments = afterHost.split('/')
            if (segments.size < 2 || segments.last().isBlank()) return null
            val key = segments.last()
            return t.removeSuffix(key) to key
        }
    }
}

/** Hitung kebutuhan upload siaran. */
object UploadBudget {
    fun requiredKbps(videoKbps: Int, audioKbps: Int, destinations: Int = 1): Int = (videoKbps + audioKbps) * destinations

    /** Status: OK jika kebutuhan <= 75% uplink terukur, WARN jika <= 100%, OVER jika lebih. */
    fun verdict(requiredKbps: Int, measuredKbps: Int?): String = when {
        measuredKbps == null || measuredKbps <= 0 -> "UNKNOWN"
        requiredKbps <= measuredKbps * 0.75 -> "OK"
        requiredKbps <= measuredKbps -> "WARN"
        else -> "OVER"
    }
}
