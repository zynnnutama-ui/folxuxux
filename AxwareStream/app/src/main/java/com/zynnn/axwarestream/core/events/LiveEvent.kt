package com.zynnn.axwarestream.core.events

import org.json.JSONObject
import java.util.UUID

/** Sumber event: TikTok LIVE, webhook lokal (mis. donasi), atau sistem aplikasi. */
enum class Platform(val label: String, val colorHex: String) {
    TIKTOK("TikTok", "#FE2C55"),
    WEBHOOK("Webhook", "#FFB020"),
    SYSTEM("Axware", "#25F4EE");

    companion object {
        fun parse(value: String?): Platform =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: WEBHOOK
    }
}

/** Jenis event TikTok LIVE yang dikenal widget overlay. */
enum class EventType(val label: String) {
    CHAT("Komentar"),
    GIFT("Gift"),
    FOLLOW("Follow"),
    LIKE("Like"),
    SHARE("Share"),
    /** Penonton memposting ulang (repost) live ke profil/beranda mereka. */
    REPOST("Repost"),
    JOIN("Join"),
    SUBSCRIBE("Subscribe"),
    DONATION("Donasi"),
    SYSTEM("Sistem");

    companion object {
        fun parse(value: String?): EventType {
            val v = value?.trim().orEmpty()
            if (v.equals("COMMENT", true)) return CHAT
            return entries.firstOrNull { it.name.equals(v, ignoreCase = true) } ?: CHAT
        }
    }
}

data class LiveEvent(
    val platform: Platform,
    val type: EventType,
    /** Nama tampilan (nickname TikTok). */
    val user: String,
    val message: String = "",
    /** Jumlah: gift = jumlah gift (combo), like = jumlah like, donasi = nominal. */
    val amount: Long = 0,
    /** Teks jumlah, mis. "x5" atau "Rp50.000". */
    val amountText: String = "",
    val userColor: String = "",
    /** Total diamond untuk event gift (harga diamond gift × jumlah). */
    val diamonds: Long = 0,
    /** @username TikTok (unique id). */
    val handle: String = "",
    val avatar: String = "",
    val giftImage: String = "",
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** Poin leaderboard interaksi: gift bernilai diamond, like & komentar bernilai kecil. */
    val points: Long
        get() = when (type) {
            EventType.CHAT -> 1
            EventType.LIKE -> maxOf(1, amount)
            EventType.FOLLOW, EventType.SHARE, EventType.REPOST -> 5
            EventType.SUBSCRIBE -> 50 * maxOf(1, amount)
            EventType.GIFT -> if (diamonds > 0) diamonds * 10 else 10 * maxOf(1, amount)
            EventType.DONATION -> maxOf(10, amount / 1000)
            EventType.JOIN, EventType.SYSTEM -> 0
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("platform", platform.name)
        .put("platformLabel", platform.label)
        .put("platformColor", platform.colorHex)
        .put("type", type.name)
        .put("typeLabel", type.label)
        .put("user", user)
        .put("handle", handle)
        .put("avatar", avatar)
        .put("message", message)
        .put("amount", amount)
        .put("amountText", amountText)
        .put("diamonds", diamonds)
        .put("giftImage", giftImage)
        .put("userColor", userColor)
        .put("timestamp", timestamp)

    companion object {
        /** Membaca event dari JSON webhook. Field minimal: user. */
        fun fromWebhookJson(json: JSONObject): LiveEvent {
            val user = json.optString("user").ifBlank {
                json.optString("donator_name").ifBlank { json.optString("supporter_name").ifBlank { "Anonim" } }
            }
            val amountAny = json.opt("amount") ?: json.opt("amount_raw")
            val amount = when (amountAny) {
                is Number -> amountAny.toLong()
                is String -> amountAny.filter { it.isDigit() }.toLongOrNull() ?: 0L
                else -> 0L
            }
            val type = if (json.has("type")) EventType.parse(json.optString("type"))
            else if (amount > 0) EventType.DONATION else EventType.CHAT
            return LiveEvent(
                platform = Platform.parse(json.optString("platform", "WEBHOOK")),
                type = type,
                user = user.take(64),
                message = json.optString("message").ifBlank { json.optString("donator_message") }.take(500),
                amount = amount,
                amountText = json.optString("amountText").take(32),
                diamonds = json.optLong("diamonds", 0).coerceAtLeast(0),
            )
        }
    }
}
