package com.zynnn.axwarestream.core.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ElementType(val label: String, val dynamic: Boolean) {
    TEXT("Teks", false),
    RUNNING_TEXT("Running Text", false),
    SHAPE("Bentuk", false),
    IMAGE("Gambar", false),
    CLOCK("Jam", false),
    DYNAMIC_TEXT("Teks Dinamis", true),
    CHAT("Chat Box", true),
    EVENT_FEED("Event Feed", true),
    LEADERBOARD("Leaderboard", true),
    COUNTER("Counter", true),
    PROGRESS("Progress Goal", true),
    ALERT("Alert Popup", true),
    LIVE_TICKER("Running Text Live", true),
    /** Halaman web transparan (Browser Source), mis. Alert Sociabuzz / Saweria. */
    WEB("Web / Alert Sociabuzz", true);

    companion object {
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: TEXT
    }
}

enum class Orientation(val label: String, val width: Int, val height: Int) {
    PORTRAIT("Portrait 9:16", 1080, 1920),
    LANDSCAPE("Landscape 16:9", 1920, 1080);

    companion object {
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: PORTRAIT
    }
}

enum class Transition(val label: String) {
    NONE("Tanpa"), FADE("Fade"), SLIDE("Slide"), ZOOM("Zoom"), MORPH("Morph");

    companion object {
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: FADE
    }
}

enum class Animation(val label: String) {
    NONE("Tanpa"), PULSE("Pulse"), FLOAT("Melayang"), BLINK("Kedip"), SPIN("Putar");

    companion object {
        fun parse(v: String?) = entries.firstOrNull { it.name == v } ?: NONE
    }
}

/**
 * Satu elemen di kanvas overlay. Posisi & ukuran dalam piksel kanvas (1080x1920 atau 1920x1080).
 * Warna dalam format ARGB Long (0xAARRGGBB).
 */
data class OverlayElement(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: ElementType,
    val name: String = type.label,
    val x: Float = 100f,
    val y: Float = 100f,
    val w: Float = 600f,
    val h: Float = 200f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val text: String = "",
    val fontSize: Float = 48f,
    val bold: Boolean = true,
    val align: String = "LEFT",
    val textColor: Long = 0xFFFFFFFF,
    val bgColor: Long = 0x00000000,
    val accentColor: Long = 0xFFFF2D55,
    val borderColor: Long = 0x00000000,
    val borderWidth: Float = 0f,
    val cornerRadius: Float = 24f,
    val shape: String = "RECT",
    val media: String = "",
    val metric: String = "CHAT",
    val goal: Long = 100,
    val maxItems: Int = 6,
    val speed: Float = 120f,
    val animation: Animation = Animation.NONE,
    val trigger: String = "ALL",
    val durationSec: Int = 5,
    val url: String = "",
    val pageScale: Float = 1f,
    val fit: ImageFitMode = ImageFitMode.CONTAIN,
    val imgZoom: Float = 1f,
    val imgPanX: Float = 0f,
    val imgPanY: Float = 0f,
    val flipH: Boolean = false,
    val lockRatio: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
        .put("rotation", rotation.toDouble()).put("opacity", opacity.toDouble())
        .put("visible", visible).put("locked", locked)
        .put("text", text).put("fontSize", fontSize.toDouble()).put("bold", bold).put("align", align)
        .put("textColor", textColor).put("bgColor", bgColor).put("accentColor", accentColor)
        .put("borderColor", borderColor).put("borderWidth", borderWidth.toDouble())
        .put("cornerRadius", cornerRadius.toDouble()).put("shape", shape).put("media", media)
        .put("metric", metric).put("goal", goal).put("maxItems", maxItems).put("speed", speed.toDouble())
        .put("animation", animation.name).put("trigger", trigger).put("durationSec", durationSec)
        .put("url", url).put("pageScale", pageScale.toDouble())
        .put("fit", fit.name).put("imgZoom", imgZoom.toDouble()).put("imgPanX", imgPanX.toDouble()).put("imgPanY", imgPanY.toDouble())
        .put("flipH", flipH).put("lockRatio", lockRatio)

    companion object {
        fun fromJson(o: JSONObject): OverlayElement {
            val type = ElementType.parse(o.optString("type"))
            return OverlayElement(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                type = type,
                name = o.optString("name", type.label),
                x = o.optDouble("x", 100.0).toFloat(), y = o.optDouble("y", 100.0).toFloat(),
                w = o.optDouble("w", 600.0).toFloat(), h = o.optDouble("h", 200.0).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(), opacity = o.optDouble("opacity", 1.0).toFloat(),
                visible = o.optBoolean("visible", true), locked = o.optBoolean("locked", false),
                text = o.optString("text"), fontSize = o.optDouble("fontSize", 48.0).toFloat(),
                bold = o.optBoolean("bold", true), align = o.optString("align", "LEFT"),
                textColor = o.optLong("textColor", 0xFFFFFFFF), bgColor = o.optLong("bgColor", 0),
                accentColor = o.optLong("accentColor", 0xFFFF2D55), borderColor = o.optLong("borderColor", 0),
                borderWidth = o.optDouble("borderWidth", 0.0).toFloat(),
                cornerRadius = o.optDouble("cornerRadius", 24.0).toFloat(), shape = o.optString("shape", "RECT"),
                media = o.optString("media"), metric = o.optString("metric", "CHAT"), goal = o.optLong("goal", 100),
                maxItems = o.optInt("maxItems", 6), speed = o.optDouble("speed", 120.0).toFloat(),
                animation = Animation.parse(o.optString("animation")), trigger = o.optString("trigger", "ALL"),
                durationSec = o.optInt("durationSec", 5),
                url = o.optString("url"), pageScale = o.optDouble("pageScale", 1.0).toFloat(),
                fit = ImageFitMode.parse(o.optString("fit")),
                imgZoom = o.optDouble("imgZoom", 1.0).toFloat().coerceIn(ImageFit.ZOOM_MIN, ImageFit.ZOOM_MAX),
                imgPanX = o.optDouble("imgPanX", 0.0).toFloat().coerceIn(-1f, 1f),
                imgPanY = o.optDouble("imgPanY", 0.0).toFloat().coerceIn(-1f, 1f),
                flipH = o.optBoolean("flipH", false), lockRatio = o.optBoolean("lockRatio", true),
            )
        }

        /** Elemen bawaan yang masuk akal saat ditambahkan dari library elemen (setiap sudut membulat). */
        fun create(type: ElementType, canvasW: Int, canvasH: Int): OverlayElement {
            val cx = canvasW / 2f
            val base = OverlayElement(type = type)
            return when (type) {
                ElementType.TEXT -> base.copy(text = "Judul Live", x = 80f, y = 120f, w = canvasW - 160f, h = 120f, fontSize = 72f)
                ElementType.RUNNING_TEXT -> base.copy(text = "Selamat datang di live! Tap ❤ untuk like, follow & share ya ✦",
                    x = 0f, y = canvasH - 140f, w = canvasW.toFloat(), h = 90f, fontSize = 44f, bgColor = 0xCC0B0B12, cornerRadius = 24f)
                ElementType.SHAPE -> base.copy(x = cx - 200, y = 300f, w = 400f, h = 400f, bgColor = 0x55FF2D55,
                    borderColor = 0xFFFF2D55, borderWidth = 4f, cornerRadius = 48f)
                ElementType.IMAGE -> base.copy(x = cx - 200, y = 300f, w = 400f, h = 400f, cornerRadius = 24f, fit = ImageFitMode.CONTAIN)
                ElementType.WEB -> base.copy(name = "Alert Sociabuzz", x = 40f, y = canvasH * 0.22f, w = canvasW - 80f,
                    h = minOf(canvasH * 0.4f, (canvasW - 80f) * 0.75f), align = "CENTER", cornerRadius = 24f)
                ElementType.CLOCK -> base.copy(x = canvasW - 360f, y = 60f, w = 300f, h = 100f, fontSize = 56f,
                    bgColor = 0x990B0B12, align = "CENTER")
                ElementType.DYNAMIC_TEXT -> base.copy(text = "👀 {viewers}  ❤ {like}  💎 {diamond}", x = 60f, y = 60f,
                    w = 760f, h = 100f, fontSize = 44f, bgColor = 0x990B0B12)
                ElementType.CHAT -> base.copy(x = 40f, y = canvasH * 0.55f, w = minOf(760f, canvasW * 0.7f), h = canvasH * 0.3f,
                    fontSize = 36f, bgColor = 0x660B0B12, maxItems = 7)
                ElementType.EVENT_FEED -> base.copy(x = canvasW - 620f, y = canvasH * 0.3f, w = 580f, h = 420f,
                    fontSize = 32f, bgColor = 0x660B0B12, maxItems = 6)
                ElementType.LEADERBOARD -> base.copy(text = "Top Gifter", x = 40f, y = 220f, w = 560f, h = 460f,
                    fontSize = 34f, bgColor = 0xCC0B0B12, maxItems = 5, metric = "DIAMOND", accentColor = 0xFF00F2FE)
                ElementType.COUNTER -> base.copy(text = "Chat", x = 40f, y = 180f, w = 320f, h = 150f, fontSize = 64f,
                    bgColor = 0xCC0B0B12, metric = "CHAT", align = "CENTER")
                ElementType.PROGRESS -> base.copy(text = "Goal Like", x = 60f, y = 360f, w = canvasW - 120f, h = 120f,
                    fontSize = 36f, bgColor = 0xCC0B0B12, metric = "LIKE", goal = 1000)
                ElementType.ALERT -> base.copy(text = "{user} • {message} {amount}", x = cx - 420, y = canvasH * 0.18f,
                    w = 840f, h = 220f, fontSize = 48f, bgColor = 0xEEFF2D55, align = "CENTER", trigger = "ALL",
                    animation = Animation.PULSE)
                ElementType.LIVE_TICKER -> base.copy(x = 0f, y = canvasH - 240f, w = canvasW.toFloat(), h = 80f,
                    fontSize = 38f, bgColor = 0xCCFF2D55, cornerRadius = 24f, speed = 140f)
            }
        }
    }
}

data class OverlayScene(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "Scene",
    val orientation: Orientation = Orientation.PORTRAIT,
    val transition: Transition = Transition.FADE,
    val elements: List<OverlayElement> = emptyList(),
) {
    val width get() = orientation.width
    val height get() = orientation.height

    fun toJson(): JSONObject {
        val arr = JSONArray()
        elements.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("name", name).put("orientation", orientation.name)
            .put("width", width).put("height", height).put("transition", transition.name).put("elements", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): OverlayScene {
            val arr = o.optJSONArray("elements") ?: JSONArray()
            return OverlayScene(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                name = o.optString("name", "Scene"),
                orientation = Orientation.parse(o.optString("orientation")),
                transition = Transition.parse(o.optString("transition")),
                elements = (0 until arr.length()).map { OverlayElement.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

data class OverlayProject(
    val scenes: List<OverlayScene>,
    val activeSceneId: String,
    val templates: List<OverlayScene> = emptyList(),
    val revision: Long = 0,
) {
    val activeScene: OverlayScene get() = scenes.firstOrNull { it.id == activeSceneId } ?: scenes.first()

    fun toJson(): JSONObject {
        val s = JSONArray(); scenes.forEach { s.put(it.toJson()) }
        val t = JSONArray(); templates.forEach { t.put(it.toJson()) }
        return JSONObject().put("scenes", s).put("activeSceneId", activeSceneId).put("templates", t)
            .put("revision", revision)
    }

    companion object {
        fun fromJson(o: JSONObject): OverlayProject {
            val s = o.optJSONArray("scenes") ?: JSONArray()
            val t = o.optJSONArray("templates") ?: JSONArray()
            val scenes = (0 until s.length()).map { OverlayScene.fromJson(s.getJSONObject(it)) }
                .ifEmpty { listOf(Templates.gaming()) }
            return OverlayProject(
                scenes = scenes,
                activeSceneId = o.optString("activeSceneId").takeIf { id -> scenes.any { it.id == id } } ?: scenes.first().id,
                templates = (0 until t.length()).map { OverlayScene.fromJson(t.getJSONObject(it)) },
                revision = o.optLong("revision", 0),
            )
        }

        fun default(): OverlayProject {
            val g = Templates.gaming()
            return OverlayProject(listOf(g, Templates.justChatting()), g.id)
        }
    }
}

/** Preset bawaan khusus TikTok LIVE (portrait 9:16). */
object Templates {
    fun gaming(): OverlayScene {
        val o = Orientation.PORTRAIT
        val w = o.width; val h = o.height
        return OverlayScene(name = "TikTok Gaming", orientation = o, transition = Transition.FADE, elements = listOf(
            OverlayElement.create(ElementType.DYNAMIC_TEXT, w, h).copy(name = "Info Live"),
            OverlayElement.create(ElementType.LEADERBOARD, w, h).copy(y = 200f),
            OverlayElement.create(ElementType.CHAT, w, h),
            OverlayElement.create(ElementType.ALERT, w, h),
            OverlayElement.create(ElementType.RUNNING_TEXT, w, h),
        ))
    }

    fun justChatting(): OverlayScene {
        val o = Orientation.PORTRAIT
        val w = o.width; val h = o.height
        return OverlayScene(name = "TikTok Ngobrol", orientation = o, transition = Transition.SLIDE, elements = listOf(
            OverlayElement.create(ElementType.PROGRESS, w, h).copy(name = "Goal Like", text = "Goal Like", metric = "LIKE", goal = 10000,
                x = 60f, y = 200f, w = w - 120f, h = 120f),
            OverlayElement.create(ElementType.PROGRESS, w, h).copy(name = "Goal Diamond", text = "Goal Diamond", metric = "DIAMOND", goal = 1000,
                x = 60f, y = 340f, w = w - 120f, h = 120f, accentColor = 0xFF00F2FE),
            OverlayElement.create(ElementType.EVENT_FEED, w, h).copy(y = 560f, h = 440f),
            OverlayElement.create(ElementType.CHAT, w, h),
            OverlayElement.create(ElementType.ALERT, w, h).copy(trigger = "FOLLOW", text = "Makasih follow-nya, {user}!",
                bgColor = 0xEE161823, borderColor = 0xFF00F2FE, borderWidth = 4f),
            OverlayElement.create(ElementType.ALERT, w, h).copy(trigger = "GIFT", text = "{user} mengirim {message} {amount}"),
        ))
    }

    fun liveSelling(): OverlayScene {
        val o = Orientation.PORTRAIT
        val w = o.width; val h = o.height
        return OverlayScene(name = "Live Jualan", orientation = o, transition = Transition.ZOOM, elements = listOf(
            OverlayElement.create(ElementType.SHAPE, w, h).copy(name = "Banner", x = 40f, y = 220f, w = w - 80f, h = 220f,
                bgColor = 0xEEFF2D55, borderWidth = 0f, cornerRadius = 36f),
            OverlayElement.create(ElementType.TEXT, w, h).copy(text = "DISKON 50% HARI INI", x = 70f, y = 270f, w = w - 140f,
                h = 120f, fontSize = 76f, align = "CENTER", animation = Animation.PULSE),
            OverlayElement.create(ElementType.COUNTER, w, h).copy(text = "Like", metric = "LIKE", x = 40f, y = 480f),
            OverlayElement.create(ElementType.COUNTER, w, h).copy(text = "Penonton", metric = "VIEWERS", x = 380f, y = 480f, accentColor = 0xFF00F2FE),
            OverlayElement.create(ElementType.EVENT_FEED, w, h).copy(x = w - 600f, y = 680f, w = 560f, h = 340f),
            OverlayElement.create(ElementType.CHAT, w, h),
            OverlayElement.create(ElementType.ALERT, w, h).copy(trigger = "GIFT", text = "Terima kasih {user}! {message} {amount}"),
        ))
    }

    fun landscape(): OverlayScene {
        val o = Orientation.LANDSCAPE
        val w = o.width; val h = o.height
        return OverlayScene(name = "TikTok Landscape", orientation = o, transition = Transition.FADE, elements = listOf(
            OverlayElement.create(ElementType.DYNAMIC_TEXT, w, h).copy(x = 60f, y = 50f),
            OverlayElement.create(ElementType.CLOCK, w, h),
            OverlayElement.create(ElementType.CHAT, w, h).copy(x = w - 640f, y = 200f, w = 600f, h = 620f),
            OverlayElement.create(ElementType.LEADERBOARD, w, h).copy(x = 60f, y = 200f, w = 520f, h = 440f),
            OverlayElement.create(ElementType.ALERT, w, h).copy(x = w / 2f - 420f, y = 120f),
            OverlayElement.create(ElementType.LIVE_TICKER, w, h).copy(y = h - 110f),
        ))
    }

    fun builtIn(): List<OverlayScene> = listOf(gaming(), justChatting(), liveSelling(), landscape())
}
