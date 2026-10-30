package com.zynnn.axwarestream.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.MainActivity
import com.zynnn.axwarestream.R
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import kotlin.math.abs

/**
 * Jendela "Komentar live" untuk streamer: daftar komentar TikTok terbaru yang mengambang di atas game,
 * supaya streamer bisa membaca komentar tanpa membuka aplikasi lain. Ini BUKAN elemen overlay siaran:
 * tidak digambar ke video oleh Axware. Jendela ini hanya dapat ditutup/dinonaktifkan langsung lewat
 * aplikasi Axware Stream.
 */
class StreamerChatService : Service() {

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var list: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var collapsed = false
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as AxwareApp

    private val listener: (LiveEvent) -> Unit = { e -> if (wanted(e)) main.post { onNewEvent(e) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        if (root == null) show()
        running = true
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, AxwareApp.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Komentar live aktif")
            .setContentText("Komentar TikTok tampil di jendela kecil untuk kamu baca saat live.")
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private var scroll: ScrollView? = null
    private var newChip: TextView? = null
    private var unread = 0
    private var heightDp = DEFAULT_HEIGHT_DP
    private var sizeLabel: TextView? = null
    private var shrinkBtn: TextView? = null
    private var growBtn: TextView? = null
    private var listFrameView: FrameLayout? = null
    private var footerView: LinearLayout? = null

    private fun maxHeightDp(): Float {
        val dm = resources.displayMetrics
        val screenDp = dm.heightPixels / dm.density
        return clampHeight(screenDp * 0.7f, MIN_HEIGHT_DP, MAX_HEIGHT_DP).coerceAtLeast(MIN_HEIGHT_DP)
    }

    fun setListHeight(dpValue: Float, save: Boolean = true) {
        val wasBottom = atBottom()
        heightDp = clampHeight(dpValue, MIN_HEIGHT_DP, maxHeightDp())
        listFrameView?.let { f ->
            f.layoutParams = (f.layoutParams as LinearLayout.LayoutParams).apply { height = dp(heightDp).toInt() }
        }
        if (save) prefs.edit().putFloat(PREF_HEIGHT, heightDp).apply()
        updateSizeUi()
        keepOnScreen()
        if (wasBottom) scrollToBottom(false)
    }

    private fun keepOnScreen() {
        val r = root ?: return; val p = lp ?: return
        r.post {
            val screenH = resources.displayMetrics.heightPixels
            val over = p.y + r.height - screenH
            if (over > 0 && r.height > 0) {
                p.y = (p.y - over).coerceAtLeast(0)
                runCatching { wm.updateViewLayout(r, p) }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSizeUi() {
        val max = maxHeightDp()
        sizeLabel?.text = "Ukuran ${heightDp.toInt()} dp"
        shrinkBtn?.let { it.isEnabled = heightDp > MIN_HEIGHT_DP + 0.5f; it.alpha = if (it.isEnabled) 1f else 0.35f }
        growBtn?.let { it.isEnabled = heightDp < max - 0.5f; it.alpha = if (it.isEnabled) 1f else 0.35f }
    }

    private fun atBottom(): Boolean {
        val sv = scroll ?: return true
        val child = sv.getChildAt(0) ?: return true
        return child.height - (sv.scrollY + sv.height) <= dp(24f)
    }

    private fun scrollToBottom(smooth: Boolean) {
        val sv = scroll ?: return
        sv.post {
            val y = (sv.getChildAt(0)?.height ?: 0) - sv.height
            if (smooth) sv.smoothScrollTo(0, y.coerceAtLeast(0)) else sv.scrollTo(0, y.coerceAtLeast(0))
        }
        unread = 0; updateChip()
    }

    @SuppressLint("SetTextI18n")
    private fun updateChip() {
        val c = newChip ?: return
        if (unread > 0) { c.text = "↓ $unread baru"; c.visibility = View.VISIBLE } else c.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun show() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(0xEB0B101E.toInt()); setStroke(dp(1f).toInt(), 0x2EFFFFFF) }
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(10f).toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "● Komentar live"; setTextColor(0xFFFF2D55.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
        }
        fun headerBtn(label: String) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            minWidth = dp(30f).toInt(); setPadding(dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt(), dp(2f).toInt())
        }
        val toggle = headerBtn("–").apply {
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0x1FFFFFFF) }
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(toggle)

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        FILTERS.forEach { f ->
            val chipV = TextView(this).apply {
                text = f.label; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(dp(6.5f).toInt(), dp(3f).toInt(), dp(6.5f).toInt(), dp(3f).toInt())
                tag = f.key
            }
            fun paint() {
                val on = filterOn(f.key)
                chipV.setTextColor(if (on) Color.WHITE else 0xFF94A3B8.toInt())
                chipV.background = GradientDrawable().apply {
                    cornerRadius = dp(50f)
                    if (on) setColor((f.color and 0x00FFFFFF) or 0x55000000) else setColor(0x00000000)
                    setStroke(dp(1f).toInt(), if (on) f.color else 0x26FFFFFF)
                }
            }
            paint()
            chipV.setOnClickListener { setFilter(f.key, !filterOn(f.key)); paint() }
            filterRow.addView(chipV, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(3f).toInt() })
        }
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(filterRow)
            setPadding(0, dp(2f).toInt(), 0, dp(4f).toInt())
        }
        filters = filterRow

        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt()) }
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = false
            addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnScrollChangeListener { _, _, _, _, _ -> if (atBottom() && unread > 0) { unread = 0; updateChip() } }
        }
        val chip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(50f); setColor(0xFFFF2D55.toInt()) }
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
            visibility = View.GONE
            setOnClickListener { scrollToBottom(smooth = true) }
        }
        val listFrame = FrameLayout(this).apply {
            addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(4f).toInt() })
        }
        heightDp = clampHeight(prefs.getFloat(PREF_HEIGHT, DEFAULT_HEIGHT_DP), MIN_HEIGHT_DP, maxHeightDp())

        fun sizeBtn(label: String, desc: String) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            contentDescription = desc
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(0x1FFFFFFF) }
            setPadding(0, dp(2f).toInt(), 0, dp(2f).toInt())
        }
        val shrink = sizeBtn("−", "Perkecil jendela komentar").apply { tag = "shrink" }
        val grow = sizeBtn("+", "Perbesar jendela komentar").apply { tag = "grow" }
        val grip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = "grip"
            contentDescription = "Seret ke atas/bawah untuk mengubah tinggi"
            addView(View(this@StreamerChatService).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4f); setColor(0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(34f).toInt(), dp(3.5f).toInt()).apply { bottomMargin = dp(2f).toInt() })
            addView(TextView(this@StreamerChatService).apply {
                setTextColor(0xFF94A3B8.toInt()); textSize = 9.5f; gravity = Gravity.CENTER
                sizeLabel = this
            })
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f).toInt(), 0, 0)
            addView(shrink, LinearLayout.LayoutParams(dp(40f).toInt(), dp(26f).toInt()))
            addView(grip, LinearLayout.LayoutParams(0, dp(30f).toInt(), 1f))
            addView(grow, LinearLayout.LayoutParams(dp(40f).toInt(), dp(26f).toInt()))
        }
        shrink.setOnClickListener { setListHeight(heightDp - STEP_DP) }
        grow.setOnClickListener { setListHeight(heightDp + STEP_DP) }
        var gy = 0f; var gh = 0f
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { gy = e.rawY; gh = heightDp }
                MotionEvent.ACTION_MOVE -> setListHeight(gh + (e.rawY - gy) / resources.displayMetrics.density, save = false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setListHeight(heightDp, save = true)
            }
            true
        }

        box.addView(header)
        box.addView(filterScroll)
        box.addView(listFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp).toInt()))
        box.addView(footer)
        listFrameView = listFrame; footerView = footer; shrinkBtn = shrink; growBtn = grow
        updateSizeUi()
        val w = dp(250f).toInt()
        val p = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8f).toInt(); y = dp(120f).toInt() }
        toggle.setOnClickListener {
            collapsed = !collapsed
            listFrame.visibility = if (collapsed) View.GONE else View.VISIBLE
            filterScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
            footer.visibility = if (collapsed) View.GONE else View.VISIBLE
            toggle.text = if (collapsed) "+" else "–"
            p.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else w
            runCatching { wm.updateViewLayout(box, p) }
            if (!collapsed) scrollToBottom(false)
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y }
                MotionEvent.ACTION_MOVE -> if (abs(e.rawX - dx) > 4 || abs(e.rawY - dy) > 4) {
                    p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt(); runCatching { wm.updateViewLayout(box, p) }
                }
            }
            true
        }
        wm.addView(box, p)
        root = box; list = items; lp = p; scroll = sv; newChip = chip
        app.hub.recent(MAX_ROWS * 2).filter(::wanted).takeLast(MAX_ROWS).forEach { addRow(it) }
        if (items.childCount == 0) showEmpty()
        app.hub.addListener(listener)
        scrollToBottom(false)
    }

    private val prefs by lazy { getSharedPreferences("streamer_chat", Context.MODE_PRIVATE) }
    private var filters: LinearLayout? = null

    fun filterOn(key: String) = prefs.getBoolean("show_$key", true)
    private fun setFilter(key: String, on: Boolean) { prefs.edit().putBoolean("show_$key", on).apply() }

    private fun wanted(e: LiveEvent): Boolean = when (e.type) {
        EventType.CHAT -> true
        EventType.GIFT, EventType.DONATION, EventType.SUBSCRIBE -> filterOn("gift")
        EventType.JOIN -> filterOn("join")
        EventType.FOLLOW -> filterOn("follow")
        EventType.SHARE -> filterOn("share")
        EventType.REPOST -> filterOn("repost")
        else -> false
    }

    private var emptyView: TextView? = null
    private var joinRow: TextView? = null
    private var joinNames = ArrayDeque<String>()
    private var joinTotal = 0

    private fun showEmpty() {
        val items = list ?: return
        emptyView = TextView(this).apply {
            text = if (app.tiktok.active) "Menunggu komentar…" else "Event TikTok belum tersambung"
            setTextColor(0xFF94A3B8.toInt()); textSize = 12f
        }
        items.addView(emptyView)
    }

    private fun onNewEvent(e: LiveEvent) {
        val items = list ?: return
        if (!wanted(e)) return
        val stick = atBottom()
        val added = addRow(e)
        while (items.childCount > MAX_ROWS) items.removeViewAt(0)
        if (stick || collapsed) scrollToBottom(smooth = true) else if (added) { unread++; updateChip() }
    }

    private fun addRow(e: LiveEvent): Boolean {
        val items = list ?: return false
        emptyView?.let { items.removeView(it); emptyView = null }
        val name = e.user.ifBlank { "penonton" }
        if (e.type == EventType.JOIN) {
            val last = joinRow
            if (last != null && last.parent === items && items.getChildAt(items.childCount - 1) === last) {
                joinTotal++
                joinNames.addFirst(name); while (joinNames.size > 2) joinNames.removeLast()
                last.text = joinText()
                return false
            }
            joinTotal = 1; joinNames.clear(); joinNames.addFirst(name)
            val tv = rowView(joinText())
            items.addView(tv); joinRow = tv
            return true
        }
        items.addView(rowView(rowText(e)))
        return true
    }

    private fun joinText(): CharSequence {
        val sb = SpannableStringBuilder()
        badge(sb, "Masuk", TYPE_COLORS.getValue(EventType.JOIN))
        val who = joinNames.joinToString(", ")
        val st = sb.length; sb.append(who)
        sb.setSpan(ForegroundColorSpan(0xFFDADAE3.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val more = joinTotal - joinNames.size
        val tail = (if (more > 0) " dan $more lainnya" else "") + " bergabung"
        val t0 = sb.length; sb.append(tail)
        sb.setSpan(ForegroundColorSpan(0xFF94A3B8.toInt()), t0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun rowText(e: LiveEvent): CharSequence {
        val sb = SpannableStringBuilder()
        val name = e.user.ifBlank { "penonton" }
        val color = TYPE_COLORS[e.type]
        if (e.type != EventType.CHAT && color != null) badge(sb, e.type.label, color)
        val st = sb.length; sb.append(name)
        sb.setSpan(ForegroundColorSpan(if (e.type == EventType.CHAT) 0xFF00F2FE.toInt() else (color ?: 0xFFFBBF24.toInt())), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val msg = when (e.type) {
            EventType.CHAT -> e.message
            EventType.FOLLOW -> "mengikuti kamu"
            EventType.SHARE -> "membagikan live"
            EventType.REPOST -> "memposting ulang live"
            else -> "${e.message} ${e.amountText}".trim()
        }
        sb.append("  ")
        val m0 = sb.length; sb.append(msg)
        if (e.type != EventType.CHAT && e.type != EventType.GIFT && e.type != EventType.DONATION)
            sb.setSpan(ForegroundColorSpan(0xFFDADAE3.toInt()), m0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun badge(sb: SpannableStringBuilder, label: String, color: Int) {
        val st = sb.length; sb.append(label)
        sb.setSpan(BadgeSpan(color, dp(6f), dp(5f), dp(5f)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun rowView(text: CharSequence) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 12.5f
        setPadding(0, dp(2f).toInt(), 0, dp(2f).toInt())
    }

    private class BadgeSpan(val color: Int, val radius: Float, val padH: Float, val gap: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val p = Paint(paint).apply { textSize = paint.textSize * 0.78f; typeface = Typeface.DEFAULT_BOLD }
            return (p.measureText(text, start, end) + padH * 2 + gap).toInt()
        }
        override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint(paint).apply { textSize = paint.textSize * 0.78f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val w = p.measureText(text, start, end) + padH * 2
            val fm = p.fontMetrics
            val h = (fm.descent - fm.ascent) + padH * 0.5f
            val cy = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            val r = RectF(x, cy - h / 2, x + w, cy + h / 2)
            p.color = (color and 0x00FFFFFF) or 0x40000000
            c.drawRoundRect(r, radius, radius, p)
            p.color = color
            c.drawText(text, start, end, x + padH, cy - (fm.ascent + fm.descent) / 2f, p)
        }
    }

    override fun onDestroy() {
        running = false
        app.hub.removeListener(listener)
        root?.let { runCatching { wm.removeView(it) } }
        root = null; list = null; scroll = null; newChip = null; joinRow = null; filters = null
        listFrameView = null; footerView = null; sizeLabel = null; shrinkBtn = null; growBtn = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4203
        const val MAX_ROWS = 150
        const val MIN_HEIGHT_DP = 100f
        const val MAX_HEIGHT_DP = 520f
        const val DEFAULT_HEIGHT_DP = 240f
        const val STEP_DP = 40f
        const val PREF_HEIGHT = "list_height_dp"
        fun clampHeight(v: Float, min: Float, max: Float): Float = if (v.isNaN()) DEFAULT_HEIGHT_DP.coerceIn(min, max) else v.coerceIn(min, max)
        const val ACTION_STOP = "com.zynnn.axwarestream.CHAT_STOP"

        data class FilterDef(val key: String, val label: String, val color: Int)
        val TYPE_COLORS = mapOf(
            EventType.GIFT to 0xFFFF2D55.toInt(), EventType.DONATION to 0xFFFFB020.toInt(), EventType.SUBSCRIBE to 0xFFFFC94D.toInt(),
            EventType.FOLLOW to 0xFF00F2FE.toInt(), EventType.SHARE to 0xFF10B981.toInt(), EventType.REPOST to 0xFFA98BFF.toInt(),
            EventType.JOIN to 0xFF94A3B8.toInt(),
        )
        val FILTERS = listOf(
            FilterDef("gift", "Gift", 0xFFFF2D55.toInt()), FilterDef("join", "Masuk", 0xFF94A3B8.toInt()),
            FilterDef("follow", "Follow", 0xFF00F2FE.toInt()), FilterDef("share", "Share", 0xFF10B981.toInt()),
            FilterDef("repost", "Repost", 0xFFA98BFF.toInt()),
        )
        @Volatile var running = false
            private set
    }
}
