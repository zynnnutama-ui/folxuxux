package com.zynnn.axwarestream.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class LeaderEntry(val user: String, val platform: Platform, val points: Long, val diamonds: Long = 0, val gifts: Long = 0)

/** Ringkasan statistik live TikTok. */
data class LiveStats(
    val chatCount: Long = 0,
    val giftCount: Long = 0,
    val likeCount: Long = 0,
    val followCount: Long = 0,
    val shareCount: Long = 0,
    val repostCount: Long = 0,
    val subCount: Long = 0,
    val donationTotal: Long = 0,
    val diamondTotal: Long = 0,
    val joinCount: Long = 0,
    val viewers: Map<Platform, Long> = emptyMap(),
    val leaderboard: List<LeaderEntry> = emptyList(),
    /** Top gifter berdasarkan total diamond. */
    val topGifters: List<LeaderEntry> = emptyList(),
) {
    val totalViewers: Long get() = viewers.values.sum()

    fun counter(kind: String): Long = when (kind.uppercase()) {
        "CHAT" -> chatCount
        "GIFT" -> giftCount
        "LIKE" -> likeCount
        "FOLLOW" -> followCount
        "SHARE" -> shareCount
        "REPOST" -> repostCount
        "SUBSCRIBE" -> subCount
        "DONATION" -> donationTotal
        "DIAMOND" -> diamondTotal
        "JOIN" -> joinCount
        "VIEWERS" -> totalViewers
        else -> 0
    }

    fun toJson(): JSONObject {
        val v = JSONObject()
        viewers.forEach { (p, n) -> v.put(p.name, n) }
        val lb = JSONArray()
        leaderboard.forEach {
            lb.put(JSONObject().put("user", it.user).put("platform", it.platform.name)
                .put("platformColor", it.platform.colorHex).put("points", it.points).put("diamonds", it.diamonds))
        }
        val tg = JSONArray()
        topGifters.forEach {
            tg.put(JSONObject().put("user", it.user).put("diamonds", it.diamonds).put("gifts", it.gifts).put("points", it.diamonds))
        }
        return JSONObject()
            .put("CHAT", chatCount).put("GIFT", giftCount).put("LIKE", likeCount)
            .put("FOLLOW", followCount).put("SHARE", shareCount).put("REPOST", repostCount).put("SUBSCRIBE", subCount)
            .put("DONATION", donationTotal).put("DIAMOND", diamondTotal).put("JOIN", joinCount).put("VIEWERS", totalViewers)
            .put("viewers", v).put("leaderboard", lb).put("topGifters", tg)
    }
}

/**
 * Bus event tunggal: semua komentar/gift/like/follow TikTok LIVE masuk ke sini,
 * lalu dipakai oleh dock chat, widget overlay native, dan server overlay (SSE).
 */
class EventHub(private val maxRecent: Int = 200) {

    private val lock = Any()
    private val recent = ArrayDeque<LiveEvent>()
    private val points = HashMap<String, LeaderEntry>()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    private val _recentFlow = MutableStateFlow<List<LiveEvent>>(emptyList())
    val recentFlow: StateFlow<List<LiveEvent>> = _recentFlow.asStateFlow()

    /** Listener sinkron (dipakai server SSE yang berjalan di thread biasa). */
    private val listeners = CopyOnWriteArrayList<(LiveEvent) -> Unit>()

    fun addListener(l: (LiveEvent) -> Unit) { listeners.add(l) }
    fun removeListener(l: (LiveEvent) -> Unit) { listeners.remove(l) }

    fun emit(event: LiveEvent) {
        synchronized(lock) {
            // Penonton masuk sangat sering di TikTok: tidak disimpan di riwayat agar gift/komentar tidak tergeser.
            if (event.type != EventType.JOIN) {
                recent.addLast(event)
                while (recent.size > maxRecent) recent.removeFirst()
            }
            val s = _stats.value
            var next = when (event.type) {
                EventType.CHAT -> s.copy(chatCount = s.chatCount + 1)
                EventType.GIFT -> s.copy(giftCount = s.giftCount + maxOf(1, event.amount), diamondTotal = s.diamondTotal + event.diamonds)
                EventType.LIKE -> s.copy(likeCount = s.likeCount + maxOf(1, event.amount))
                EventType.FOLLOW -> s.copy(followCount = s.followCount + 1)
                EventType.SHARE -> s.copy(shareCount = s.shareCount + 1)
                EventType.REPOST -> s.copy(repostCount = s.repostCount + 1)
                EventType.SUBSCRIBE -> s.copy(subCount = s.subCount + 1)
                EventType.DONATION -> s.copy(donationTotal = s.donationTotal + event.amount)
                EventType.JOIN -> s.copy(joinCount = s.joinCount + 1)
                else -> s
            }
            if (event.points > 0 && event.user.isNotBlank()) {
                val key = event.platform.name + ":" + event.user.lowercase()
                val old = points[key]
                val isGift = event.type == EventType.GIFT
                points[key] = LeaderEntry(event.user, event.platform, (old?.points ?: 0) + event.points,
                    (old?.diamonds ?: 0) + (if (isGift) event.diamonds else 0), (old?.gifts ?: 0) + (if (isGift) maxOf(1, event.amount) else 0))
                next = next.copy(
                    leaderboard = points.values.sortedByDescending { it.points }.take(20),
                    topGifters = if (isGift) points.values.filter { it.gifts > 0 }.sortedWith(compareByDescending<LeaderEntry> { it.diamonds }.thenByDescending { it.gifts }).take(20) else next.topGifters,
                )
            }
            _stats.value = next
            _recentFlow.value = recent.toList()
        }
        _events.tryEmit(event)
        listeners.forEach { runCatching { it(event) } }
    }

    fun setViewers(platform: Platform, count: Long) {
        synchronized(lock) {
            val s = _stats.value
            _stats.value = s.copy(viewers = s.viewers + (platform to count))
        }
    }

    /** Total like room dari TikTok (lebih akurat daripada menjumlah event like satu per satu). */
    fun setLikeTotal(total: Long) {
        synchronized(lock) {
            val s = _stats.value
            if (total > 0) _stats.value = s.copy(likeCount = total)
        }
    }

    fun clearViewers(platform: Platform) {
        synchronized(lock) {
            val s = _stats.value
            _stats.value = s.copy(viewers = s.viewers - platform)
        }
    }

    fun recent(limit: Int = 50): List<LiveEvent> = synchronized(lock) { recent.toList().takeLast(limit) }

    fun reset() {
        synchronized(lock) {
            recent.clear(); points.clear()
            _stats.value = LiveStats(viewers = _stats.value.viewers)
            _recentFlow.value = emptyList()
        }
    }

    fun stateJson(limit: Int = 50): JSONObject {
        val arr = JSONArray()
        recent(limit).forEach { arr.put(it.toJson()) }
        return JSONObject().put("stats", stats.value.toJson()).put("events", arr)
    }
}
