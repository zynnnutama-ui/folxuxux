package com.zynnn.axwarestream.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.core.stream.FitMode
import com.zynnn.axwarestream.core.stream.GameOrientation
import com.zynnn.axwarestream.core.stream.LayoutBackground
import com.zynnn.axwarestream.core.stream.NRect
import com.zynnn.axwarestream.core.stream.ScreenLayout
import com.zynnn.axwarestream.ui.theme.Ax
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.util.Locale

fun previewCaptureSize(displayW: Int, displayH: Int, orientation: GameOrientation): Pair<Int, Int> {
    val o = if (orientation == GameOrientation.AUTO) GameOrientation.LANDSCAPE else orientation
    return ScreenLayout.captureSize(displayW, displayH, o, 1280)
}

fun ScreenLayout.withPreset(preset: String, outW: Int, outH: Int, srcW: Int, srcH: Int): ScreenLayout {
    val r = contentRect(outW, outH, srcW, srcH)
    val cy = when (preset) {
        "TOP" -> r.h / 2f
        "BOTTOM" -> 1f - r.h / 2f
        else -> 0.5f
    }
    return copy(centerX = 0.5f, centerY = if (r.h >= 1f) 0.5f else cy)
}

@Composable
fun ScreenLayoutPreview(portrait: Boolean, layout: ScreenLayout, scene: OverlayScene?, live: Boolean, modifier: Modifier = Modifier) {
    val app = AxwareApp.instance
    val dm = LocalContext.current.resources.displayMetrics
    val outW = if (portrait) 720 else 1280; val outH = if (portrait) 1280 else 720
    val (srcW, srcH) = previewCaptureSize(dm.widthPixels, dm.heightPixels, layout.orientation)
    var draft by remember { mutableStateOf(layout) }
    val layoutState by rememberUpdatedState(layout)
    LaunchedEffect(layout) { if (layout != draft) draft = layout }
    LaunchedEffect(Unit) {
        snapshotFlow { draft }.drop(1).distinctUntilChanged().debounce(80).collect { d ->
            if (d != layoutState) app.settings.update { it.copy(layout = d) }
        }
    }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawLayout(draft, outW, outH, srcW, srcH) }
        if (scene != null) OverlayPreview(scene, Modifier.fillMaxSize(), drawBackdrop = false)
        Box(
            Modifier.fillMaxSize().pointerInput(portrait, srcW, srcH) {
                detectTransformGestures { _, pan, zoom, _ ->
                    draft = draft.moved(pan.x / size.width, pan.y / size.height).withZoom(draft.zoom * zoom)
                }
            },
        )
        Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (portrait) "9:16" else "16:9", Color.White)
            Pill(if (live) "Ubah langsung" else "Geser / cubit", Ax.Cyan)
        }
    }
}

private fun DrawScope.drawLayout(l: ScreenLayout, outW: Int, outH: Int, srcW: Int, srcH: Int) {
    val r = l.contentRect(outW, outH, srcW, srcH)
    val c = l.coverRect(outW, outH, srcW, srcH)
    when (l.background) {
        LayoutBackground.BLACK -> drawRect(Color.Black)
        LayoutBackground.COLOR -> drawRect(Color(l.bgColor.toInt()).copy(alpha = 1f))
        LayoutBackground.BLUR -> {
            drawMockGame(c, blurred = true)
            drawRect(Color.Black.copy(alpha = 0.55f))
        }
    }
    clipRect { drawMockGame(r, blurred = false) }
    val tl = Offset(r.x * size.width, r.y * size.height)
    drawRect(Color.White.copy(alpha = 0.85f), tl, Size(r.w * size.width, r.h * size.height),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
}

private fun DrawScope.drawMockGame(r: NRect, blurred: Boolean) {
    val x = r.x * size.width; val y = r.y * size.height; val w = r.w * size.width; val h = r.h * size.height
    if (blurred) {
        drawRect(Brush.verticalGradient(
            0f to Color(0xFF2B5876), 0.58f to Color(0xFF4A4A78), 0.78f to Color(0xFF3F6A52), 1f to Color(0xFF3A7D44),
            startY = y, endY = y + h), Offset(x, y), Size(w, h))
        val sun = Offset(x + w * 0.78f, y + h * 0.25f)
        drawCircle(Brush.radialGradient(listOf(Color(0xCCFBBF24), Color(0x00FBBF24)), center = sun, radius = h * 0.3f),
            radius = h * 0.3f, center = sun)
        return
    }
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376)), startY = y, endY = y + h), Offset(x, y), Size(w, h))
    drawRect(Color(0xFF3A7D44), Offset(x, y + h * 0.68f), Size(w, h * 0.32f))
    drawCircle(Color(0xFFFBBF24), radius = h * 0.09f, center = Offset(x + w * 0.78f, y + h * 0.25f))
    val hud = Color.White.copy(alpha = 0.85f)
    drawRect(hud, Offset(x + w * 0.03f, y + h * 0.05f), Size(w * 0.22f, h * 0.05f))
    drawRect(Color(0xFFFF2D55), Offset(x + w * 0.03f, y + h * 0.05f), Size(w * 0.15f, h * 0.05f))
    drawCircle(hud.copy(alpha = 0.35f), radius = h * 0.14f, center = Offset(x + w * 0.12f, y + h * 0.78f))
    drawCircle(hud.copy(alpha = 0.35f), radius = h * 0.1f, center = Offset(x + w * 0.88f, y + h * 0.78f))
}

@Composable
fun ScreenLayoutControls(portrait: Boolean, layout: ScreenLayout, live: Boolean) {
    val app = AxwareApp.instance
    val dm = LocalContext.current.resources.displayMetrics
    val outW = if (portrait) 720 else 1280; val outH = if (portrait) 1280 else 720
    val (srcW, srcH) = previewCaptureSize(dm.widthPixels, dm.heightPixels, layout.orientation)
    fun up(f: (ScreenLayout) -> ScreenLayout) = app.settings.update { it.copy(layout = f(it.layout)) }
    SectionCard(title = "Ukuran & posisi layar", subtitle = "Atur frame siaran dan letak layar game") {
        Text("Ukuran layar siaran", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Segmented(listOf(true, false), portrait, { if (it) "9:16 (Portrait)" else "16:9 (Landscape)" }, { p ->
            if (!live) app.settings.update { it.copy(portrait = p) }
        })
        Text("Orientasi game", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Segmented(GameOrientation.entries, layout.orientation, { it.label }, { o -> if (!live) up { it.copy(orientation = o) } })
        if (live) Text("Ukuran layar & orientasi dikunci saat live. Posisi, zoom, dan latar bisa diubah langsung.",
            style = MaterialTheme.typography.bodySmall, color = Ax.Warn)
        Segmented(FitMode.entries, layout.fit, { it.label }, { f -> up { it.copy(fit = f) } })
        SliderRow("Zoom", layout.zoom, ScreenLayout.MIN_ZOOM..ScreenLayout.MAX_ZOOM, { String.format(Locale.US, "%.2fx", it) }) { v ->
            up { it.withZoom(v) }
        }
        Text("Posisi cepat", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Segmented(listOf("TOP", "CENTER", "BOTTOM"), presetOf(layout, outW, outH, srcW, srcH),
            { mapOf("TOP" to "Atas", "CENTER" to "Tengah", "BOTTOM" to "Bawah", "" to "")[it]!! },
            { p -> up { it.withPreset(p, outW, outH, srcW, srcH) } })
        SliderRow("Posisi X", layout.centerX, -0.5f..1.5f, { "${(it * 100).toInt()}%" }) { v -> up { it.copy(centerX = v) } }
        SliderRow("Posisi Y", layout.centerY, -0.5f..1.5f, { "${(it * 100).toInt()}%" }) { v -> up { it.copy(centerY = v) } }
        Text("Latar area kosong", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Segmented(LayoutBackground.entries, layout.background, { it.label }, { b -> up { it.copy(background = b) } })
        if (layout.background == LayoutBackground.COLOR) ColorPicker("Warna latar", layout.bgColor) { c -> up { it.copy(bgColor = c or 0xFF000000) } }
        OutlinedButton(onClick = { up { ScreenLayout(orientation = it.orientation) } }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Reset tata letak")
        }
    }
}

private fun presetOf(l: ScreenLayout, outW: Int, outH: Int, srcW: Int, srcH: Int): String {
    if (kotlin.math.abs(l.centerX - 0.5f) > 0.005f) return ""
    for (p in listOf("CENTER", "TOP", "BOTTOM")) {
        if (kotlin.math.abs(l.withPreset(p, outW, outH, srcW, srcH).centerY - l.centerY) < 0.005f) return p
    }
    return ""
}
