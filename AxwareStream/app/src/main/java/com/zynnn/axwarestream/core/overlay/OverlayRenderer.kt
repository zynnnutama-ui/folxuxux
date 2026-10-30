package com.zynnn.axwarestream.core.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.LiveStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayRenderer(private val mediaDir: File) {

    data class ActiveAlert(val event: LiveEvent, val startedAt: Long)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bmpCache = LruCache<String, Bitmap>(8)
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val startTime = System.currentTimeMillis()

    private val ALERT_ALL = setOf(EventType.GIFT, EventType.FOLLOW, EventType.SHARE, EventType.REPOST, EventType.SUBSCRIBE, EventType.DONATION)

    @Volatile var webPlaceholder = false
    @Volatile var webSnapshot: ((OverlayElement) -> Bitmap?)? = null

    val activeAlerts = HashMap<String, ActiveAlert>()
    private val alertQueue = ArrayDeque<LiveEvent>()

    fun onEvent(scene: OverlayScene, e: LiveEvent) {
        if (e.type == EventType.CHAT) return
        if (scene.elements.any { it.type == ElementType.ALERT && it.visible && triggers(it, e) }) {
            synchronized(alertQueue) { alertQueue.addLast(e); while (alertQueue.size > 30) alertQueue.removeFirst() }
        }
    }

    private fun triggers(el: OverlayElement, e: LiveEvent) =
        if (el.trigger == "ALL") e.type in ALERT_ALL else el.trigger == e.type.name

    private fun pumpAlerts(scene: OverlayScene, now: Long) {
        val alerts = scene.elements.filter { it.type == ElementType.ALERT && it.visible }
        activeAlerts.entries.removeAll { (id, a) ->
            val el = alerts.firstOrNull { it.id == id }
            el == null || now - a.startedAt > el.durationSec * 1000L
        }
        if (activeAlerts.isNotEmpty()) return
        val next = synchronized(alertQueue) { alertQueue.removeFirstOrNull() } ?: return
        alerts.filter { triggers(it, next) }.forEach { activeAlerts[it.id] = ActiveAlert(next, now) }
    }

    fun draw(
        canvas: Canvas, scene: OverlayScene, stats: LiveStats, events: List<LiveEvent>,
        now: Long = System.currentTimeMillis(), onlyElementId: String? = null, showAlertPreview: Boolean = false,
    ) {
        pumpAlerts(scene, now)
        for (el in scene.elements) {
            if (!el.visible) continue
            if (onlyElementId != null && el.id != onlyElementId) continue
            drawElement(canvas, el, stats, events, now, showAlertPreview)
        }
    }

    private fun color(v: Long) = (v and 0xFFFFFFFFL).toInt()

    private fun drawBox(c: Canvas, el: OverlayElement, alpha: Float) {
        rect.set(0f, 0f, el.w, el.h)
        val r = if (el.shape == "CIRCLE") min(el.w, el.h) / 2f else el.cornerRadius
        val rawBg = if ((el.bgColor ushr 24) != 0L) el.bgColor else if (el.type == ElementType.WEB) 0xEB10172AL else 0L
        if ((rawBg ushr 24) != 0L) {
            val col = color(rawBg)
            fill.color = col; fill.alpha = (Color.alpha(col) * alpha).toInt()
            if (el.shape == "CIRCLE") c.drawOval(rect, fill) else c.drawRoundRect(rect, r, r, fill)
        }
        if (el.borderWidth > 0 && (el.borderColor ushr 24) != 0L) {
            stroke.color = color(el.borderColor); stroke.strokeWidth = el.borderWidth
            stroke.alpha = (Color.alpha(color(el.borderColor)) * alpha).toInt()
            val half = el.borderWidth / 2
            rect.set(half, half, el.w - half, el.h - half)
            if (el.shape == "CIRCLE") c.drawOval(rect, stroke) else c.drawRoundRect(rect, r, r, stroke)
        }
    }

    private fun setupText(el: OverlayElement, size: Float = el.fontSize, color: Long = el.textColor, alpha: Float = 1f, bold: Boolean = el.bold) {
        text.textSize = size
        text.color = color(color)
        text.alpha = (Color.alpha(color(color)) * alpha).toInt()
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(s: CharSequence, width: Int, align: String, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        val a = when (align) { "CENTER" -> Layout.Alignment.ALIGN_CENTER; "RIGHT" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        return StaticLayout.Builder.obtain(s, 0, s.length, text, max(1, width))
            .setAlignment(a).setIncludePad(false).setLineSpacing(0f, 1.1f)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    private fun drawTextCentered(c: Canvas, el: OverlayElement, s: String, pad: Float = el.fontSize * 0.5f) {
        val lay = layout(s, (el.w - pad * 2).toInt(), el.align, max(1, (el.h / (el.fontSize * 1.1f)).toInt()))
        c.save(); c.translate(pad, (el.h - lay.height) / 2f); lay.draw(c); c.restore()
    }

    private fun animTransform(c: Canvas, el: OverlayElement, now: Long): Boolean {
        val t = (now - startTime) / 1000.0
        when (el.animation) {
            Animation.PULSE -> { val s = 1f + 0.05f * sin(t * Math.PI * 2 / 1.6).toFloat(); c.scale(s, s, el.w / 2, el.h / 2) }
            Animation.FLOAT -> c.translate(0f, (-12 * (0.5 - 0.5 * kotlin.math.cos(t * Math.PI * 2 / 3))).toFloat())
            Animation.SPIN -> c.rotate(((t % 6) / 6 * 360).toFloat(), el.w / 2, el.h / 2)
            Animation.BLINK -> if (((t / 0.6).toInt() % 2) == 1) return false
            Animation.NONE -> {}
        }
        return true
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1000.0).replace(".0K", "K")
        else -> n.toString()
    }

    fun tokens(t: String, stats: LiveStats, last: LiveEvent?): String {
        val top = stats.leaderboard.firstOrNull()
        return t.replace("{viewers}", fmt(stats.totalViewers)).replace("{chat}", fmt(stats.chatCount))
            .replace("{gift}", fmt(stats.giftCount)).replace("{like}", fmt(stats.likeCount))
            .replace("{follow}", fmt(stats.followCount)).replace("{share}", fmt(stats.shareCount))
            .replace("{repost}", fmt(stats.repostCount))
            .replace("{donation}", fmt(stats.donationTotal)).replace("{top_user}", top?.user ?: "-")
            .replace("{diamond}", fmt(stats.diamondTotal)).replace("{top_gifter}", stats.topGifters.firstOrNull()?.user ?: "-")
            .replace("{last_user}", last?.user ?: "-").replace("{last_message}", last?.message ?: "")
            .replace("{user}", last?.user ?: "-").replace("{message}", last?.message?.ifBlank { last.type.label } ?: "")
            .replace("{amount}", last?.amountText?.ifBlank { if (last.amount > 0) fmt(last.amount) else "" } ?: "")
            .replace("{platform}", last?.platform?.label ?: "")
    }

    private fun parse(hex: String, fallback: Int) = runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private fun drawElement(c: Canvas, el: OverlayElement, stats: LiveStats, events: List<LiveEvent>, now: Long, alertPreview: Boolean) {
        val save = c.saveLayerAlpha(el.x - 40, el.y - 40, el.x + el.w + 40, el.y + el.h + 40, (el.opacity.coerceIn(0f, 1f) * 255).toInt())
        c.translate(el.x, el.y)
        if (el.rotation != 0f) c.rotate(el.rotation, el.w / 2, el.h / 2)
        if (!animTransform(c, el, now)) { c.restoreToCount(save); return }
        when (el.type) {
            ElementType.TEXT -> { drawBox(c, el, 1f); setupText(el); drawTextCentered(c, el, el.text) }
            ElementType.DYNAMIC_TEXT -> { drawBox(c, el, 1f); setupText(el); drawTextCentered(c, el, tokens(el.text, stats, events.lastOrNull())) }
            ElementType.CLOCK -> { drawBox(c, el, 1f); setupText(el); drawTextCentered(c, el, clockFmt.format(Date(now))) }
            ElementType.SHAPE -> {
                if (el.shape == "LINE") {
                    fill.color = color(el.accentColor)
                    val th = max(2f, el.borderWidth.takeIf { it > 0 } ?: 4f)
                    rect.set(0f, el.h / 2 - th / 2, el.w, el.h / 2 + th / 2)
                    c.drawRoundRect(rect, th / 2f, th / 2f, fill)
                } else drawBox(c, el, 1f)
            }
            ElementType.IMAGE -> drawImage(c, el)
            ElementType.RUNNING_TEXT, ElementType.LIVE_TICKER -> {
                drawBox(c, el, 1f)
                setupText(el)
                val s = if (el.type == ElementType.RUNNING_TEXT) el.text else
                    events.takeLast(5).joinToString("   •   ") { "${it.user}: ${it.message.ifBlank { it.type.label }}" }.ifBlank { "Menunggu event live…" }
                val tw = text.measureText(s)
                val cycle = el.w + tw
                val elapsed = (now - startTime) / 1000f * el.speed
                val x = el.w - (elapsed % cycle)
                c.save(); c.clipRect(0f, 0f, el.w, el.h)
                val fm = text.fontMetrics
                c.drawText(s, x, el.h / 2 - (fm.ascent + fm.descent) / 2, text)
                c.restore()
            }
            ElementType.CHAT, ElementType.EVENT_FEED -> drawList(c, el, events, el.type == ElementType.CHAT)
            ElementType.LEADERBOARD -> drawLeaderboard(c, el, stats)
            ElementType.COUNTER -> {
                drawBox(c, el, 1f)
                val v = stats.counter(el.metric)
                setupText(el, el.fontSize, el.accentColor, bold = true)
                val numLay = layout(fmt(v), el.w.toInt(), "CENTER", 1)
                setupText(el, el.fontSize * 0.4f, el.textColor, 0.8f, true)
                val lblLay = layout((el.text.ifBlank { el.metric }).uppercase(), el.w.toInt(), "CENTER", 1)
                val total = numLay.height + lblLay.height + el.fontSize * 0.15f
                c.save(); c.translate(0f, (el.h - total) / 2)
                setupText(el, el.fontSize, el.accentColor, bold = true); layout(fmt(v), el.w.toInt(), "CENTER", 1).draw(c)
                c.translate(0f, numLay.height + el.fontSize * 0.15f)
                setupText(el, el.fontSize * 0.4f, el.textColor, 0.8f, true); lblLay.draw(c)
                c.restore()
            }
            ElementType.PROGRESS -> {
                drawBox(c, el, 1f)
                val v = stats.counter(el.metric); val goal = max(1L, el.goal)
                val pct = min(1f, v.toFloat() / goal)
                val pad = el.fontSize * 0.6f
                setupText(el)
                val fm = text.fontMetrics
                val labelY = el.h * 0.36f - (fm.ascent + fm.descent) / 2
                c.drawText(el.text.ifBlank { "Goal" }, pad, labelY, text)
                val right = "${fmt(v)} / ${fmt(goal)}"
                c.drawText(right, el.w - pad - text.measureText(right), labelY, text)
                val barTop = el.h * 0.58f; val barH = el.h * 0.24f
                rect.set(pad, barTop, el.w - pad, barTop + barH)
                fill.color = 0x26FFFFFF; c.drawRoundRect(rect, barH / 2, barH / 2, fill)
                rect.set(pad, barTop, pad + (el.w - pad * 2) * pct, barTop + barH)
                fill.color = color(el.accentColor); if (pct > 0) c.drawRoundRect(rect, barH / 2, barH / 2, fill)
            }
            ElementType.WEB, ElementType.ALERT -> {
                if (el.type == ElementType.WEB) drawWeb(c, el)
                val active = activeAlerts[el.id]
                val ev = active?.event ?: if (alertPreview) LiveEvent(com.zynnn.axwarestream.core.events.Platform.SYSTEM,
                    EventType.GIFT, "Axware", "Rose", 5, "x5", diamonds = 5) else null
                if (ev != null) {
                    val age = if (active != null) (now - active.startedAt) / 1000f else (now.toInt() and 0x07FF) / 1000f
                    val s = if (age < 0.25f) 0.6f + 0.4f * (age / 0.25f) else 1f
                    c.scale(s, s, el.w / 2, el.h / 2)
                    drawBox(c, el, min(1f, age / 0.3f))
                    setupText(el)
                    drawTextCentered(c, el, tokens(el.text.ifBlank { "{user} • {message} {amount}" }, LiveStats(), ev))
                }
            }
        }
        c.restoreToCount(save)
    }

    private fun drawWeb(c: Canvas, el: OverlayElement) {
        val snap = webSnapshot?.invoke(el)
        if (snap != null && !snap.isRecycled) {
            rect.set(0f, 0f, el.w, el.h)
            c.drawBitmap(snap, null, rect, fill.apply { color = Color.WHITE; alpha = 255; isFilterBitmap = true })
        }
        if (!webPlaceholder) return
        rect.set(0f, 0f, el.w, el.h)
        fill.color = 0x2600F2FE; c.drawRoundRect(rect, 24f, 24f, fill)
        stroke.color = 0xCC00F2FE.toInt(); stroke.strokeWidth = 4f
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
        c.drawRoundRect(rect, 24f, 24f, stroke); stroke.pathEffect = null
        val title = if (el.url.isBlank()) "Alert Sociabuzz — tempel link overlay" else el.name.ifBlank { "Alert Sociabuzz" }
        val sub = if (el.url.isBlank()) "Studio › elemen › Link" else el.url.removePrefix("https://").removePrefix("http://")
        val big = min(56f, el.h * 0.16f).coerceAtLeast(20f)
        text.textSize = big; text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.WHITE
        val t1 = TextUtils.ellipsize(title, text, el.w - 40f, TextUtils.TruncateAt.END).toString()
        c.drawText(t1, (el.w - text.measureText(t1)) / 2, el.h / 2 - big * 0.2f, text)
        text.textSize = big * 0.6f; text.typeface = Typeface.DEFAULT; text.color = 0xCCFFFFFF.toInt()
        val t2 = TextUtils.ellipsize(sub, text, el.w - 40f, TextUtils.TruncateAt.MIDDLE).toString()
        c.drawText(t2, (el.w - text.measureText(t2)) / 2, el.h / 2 + big * 0.9f, text)
    }

    private fun drawImage(c: Canvas, el: OverlayElement) {
        val bmp = imageBitmap(el.media)
        if (bmp == null) {
            rect.set(0f, 0f, el.w, el.h)
            stroke.color = 0x88FFFFFF.toInt(); stroke.strokeWidth = 3f
            c.drawRoundRect(rect, el.cornerRadius, el.cornerRadius, stroke)
            setupText(el, 32f, 0xCCFFFFFF); drawTextCentered(c, el.copy(align = "CENTER", fontSize = 32f), "Pilih gambar")
            return
        }
        val path = Path()
        rect.set(0f, 0f, el.w, el.h)
        if (el.shape == "CIRCLE") path.addOval(rect, Path.Direction.CW) else path.addRoundRect(rect, el.cornerRadius, el.cornerRadius, Path.Direction.CW)
        c.save(); c.clipPath(path)
        if ((el.bgColor ushr 24) != 0L) { fill.color = color(el.bgColor); c.drawRect(0f, 0f, el.w, el.h, fill) }
        val r = ImageFit.rect(el.fit, el.w, el.h, bmp.width.toFloat(), bmp.height.toFloat(), el.imgZoom, el.imgPanX, el.imgPanY)
        rect.set(r[0], r[1], r[2], r[3])
        if (el.flipH) c.scale(-1f, 1f, (r[0] + r[2]) / 2f, 0f)
        c.drawBitmap(bmp, null, rect, fill.apply { color = Color.WHITE; alpha = 255; isFilterBitmap = true })
        c.restore()
        if (el.borderWidth > 0) drawBox(c, el.copy(bgColor = 0), 1f)
    }

    fun imageBitmap(name: String): Bitmap? {
        if (name.isBlank() || name.contains('/') || name.contains("..")) return null
        val f = File(mediaDir, name)
        if (!f.isFile) return null
        val key = name + "@" + f.lastModified()
        return bmpCache.get(key) ?: ImageStore.load(f)?.also { bmpCache.put(key, it) }
    }

    private fun drawList(c: Canvas, el: OverlayElement, events: List<LiveEvent>, chat: Boolean) {
        drawBox(c, el, 1f)
        val list = events.filter { if (chat) it.type == EventType.CHAT else it.type != EventType.CHAT }.takeLast(max(1, el.maxItems))
        val pad = el.fontSize * 0.4f
        c.save(); c.clipRect(0f, 0f, el.w, el.h)
        var y = el.h - pad
        val width = (el.w - pad * 2).toInt()
        for (ev in list.asReversed()) {
            val badge = if (chat) "" else ev.type.label
            val body = if (chat) ev.message else (ev.message + (if (ev.amountText.isNotBlank()) " ${ev.amountText}" else "") +
                (if (ev.diamonds > 0) " · ${fmt(ev.diamonds)} 💎" else ""))
            setupText(el, el.fontSize, el.textColor, 1f, false)
            val prefixW = if (badge.isEmpty()) 0f else run {
                text.textSize = el.fontSize * 0.62f; text.typeface = Typeface.DEFAULT_BOLD
                text.measureText(badge) + el.fontSize * 0.9f
            }
            val userColor = if (ev.userColor.isNotBlank()) parse(ev.userColor, color(el.accentColor)) else color(el.accentColor)
            val sb = android.text.SpannableStringBuilder()
            sb.append(ev.user, android.text.style.ForegroundColorSpan(userColor), 0)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, ev.user.length, 0)
            sb.append("  ").append(body)
            setupText(el, el.fontSize, el.textColor, 1f, false)
            val lay = layout(sb, (width - prefixW).toInt(), "LEFT", 3)
            y -= lay.height
            if (y < -lay.height) break
            if (badge.isNotEmpty()) {
            text.textSize = el.fontSize * 0.62f; text.typeface = Typeface.DEFAULT_BOLD
            val bw = text.measureText(badge) + el.fontSize * 0.5f
            val bh = el.fontSize * 0.95f
            rect.set(pad, y + (el.fontSize * 1.1f - bh) / 2, pad + bw, y + (el.fontSize * 1.1f + bh) / 2)
            fill.color = typeColor(ev.type)
            c.drawRoundRect(rect, bh / 2, bh / 2, fill)
            text.color = 0xFF0B0B12.toInt()
            val fm = text.fontMetrics
            c.drawText(badge, rect.left + el.fontSize * 0.25f, rect.centerY() - (fm.ascent + fm.descent) / 2, text)
            }
            c.save(); c.translate(pad + prefixW, y); setupText(el, el.fontSize, el.textColor, 1f, false); lay.draw(c); c.restore()
            y -= el.fontSize * 0.25f
        }
        c.restore()
    }

    private fun drawLeaderboard(c: Canvas, el: OverlayElement, stats: LiveStats) {
        drawBox(c, el, 1f)
        val pad = el.fontSize * 0.5f
        setupText(el)
        val fm = text.fontMetrics
        var y = pad - fm.ascent
        val byDiamond = el.metric.uppercase() != "POINTS"
        c.drawText(el.text.ifBlank { if (byDiamond) "Top Gifter" else "Top Supporters" }, pad, y, text)
        setupText(el, el.fontSize * 0.75f, el.accentColor, bold = true)
        c.drawText("LIVE", el.w - pad - text.measureText("LIVE"), y, text)
        y += fm.descent + pad * 0.6f
        val rowH = el.fontSize * 1.6f
        val medals = intArrayOf(0xFFFFD166.toInt(), 0xFFC0C7D6.toInt(), 0xFFE39B6B.toInt())
        val rows = (if (byDiamond) stats.topGifters else stats.leaderboard).take(max(1, el.maxItems))
        if (rows.isEmpty()) {
            setupText(el, el.fontSize * 0.85f, el.textColor, 0.6f, false)
            c.drawText(if (byDiamond) "Belum ada gift" else "Belum ada supporter", pad, y + rowH * 0.6f, text)
        }
        rows.forEachIndexed { i, r ->
            if (y + rowH > el.h) return
            rect.set(pad * 0.6f, y, el.w - pad * 0.6f, y + rowH - el.fontSize * 0.2f)
            fill.color = 0x0FFFFFFF; c.drawRoundRect(rect, rowH / 3, rowH / 3, fill)
            val cy = rect.centerY(); val rr = rowH * 0.3f
            fill.color = medals.getOrElse(i) { color(el.accentColor) }
            c.drawCircle(pad * 0.6f + pad + rr, cy, rr, fill)
            setupText(el, el.fontSize * 0.7f, 0xFF0B0B12, bold = true)
            val n = (i + 1).toString(); val f2 = text.fontMetrics
            c.drawText(n, pad * 0.6f + pad + rr - text.measureText(n) / 2, cy - (f2.ascent + f2.descent) / 2, text)
            setupText(el, el.fontSize * 0.9f, el.textColor, bold = true)
            val pts = if (byDiamond) "${fmt(r.diamonds)} 💎" else "${fmt(r.points)} pts"
            val ptsW = text.measureText(pts)
            val nameX = pad * 0.6f + pad * 2 + rr * 2
            val name = TextUtils.ellipsize(r.user, text, el.w - nameX - ptsW - pad * 2, TextUtils.TruncateAt.END).toString()
            val f3 = text.fontMetrics
            c.drawText(name, nameX, cy - (f3.ascent + f3.descent) / 2, text)
            text.alpha = 210
            c.drawText(pts, el.w - pad * 1.2f - ptsW, cy - (f3.ascent + f3.descent) / 2, text)
            y += rowH
        }
    }

    private fun typeColor(t: EventType): Int = when (t) {
        EventType.GIFT -> 0xFFFF2D55.toInt()
        EventType.FOLLOW -> 0xFF00F2FE.toInt()
        EventType.SHARE -> 0xFF10B981.toInt()
        EventType.REPOST -> 0xFFA98BFF.toInt()
        EventType.LIKE -> 0xFFFF6B8B.toInt()
        EventType.SUBSCRIBE -> 0xFFFFBF24.toInt()
        EventType.DONATION -> 0xFFFFB020.toInt()
        else -> 0xFF94A3B8.toInt()
    }

    fun renderToBitmap(target: Bitmap, scene: OverlayScene, stats: LiveStats, events: List<LiveEvent>) {
        target.eraseColor(Color.TRANSPARENT)
        val c = Canvas(target)
        c.scale(target.width.toFloat() / scene.width, target.height.toFloat() / scene.height)
        draw(c, scene, stats, events)
    }
}
