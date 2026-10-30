package com.zynnn.axwarestream.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.ui.theme.Ax
import kotlinx.coroutines.delay

/**
 * Menggambar scene overlay memakai renderer yang sama dengan stream & jendela mengambang,
 * sehingga yang terlihat di editor = yang tampil di siaran.
 * Saat pratinjau alert aktif (di Beranda Live maupun Studio), kotak placeholder URL disembunyikan
 * dan animasi popup alert ditampilkan secara langsung.
 */
@Composable
fun OverlayPreview(
    scene: OverlayScene,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    editable: Boolean = false,
    showAlertPreview: Boolean = false,
    drawBackdrop: Boolean = true,
    onSelect: (String?) -> Unit = {},
    onMove: (OverlayElement, Float, Float) -> Unit = { _, _, _ -> },
) {
    val app = AxwareApp.instance
    val effectiveAlertPreview = if (!drawBackdrop) Ax.alertPreview.value else showAlertPreview
    val renderer = remember { app.newRenderer() }
    renderer.webPlaceholder = editable && !effectiveAlertPreview
    var tick by remember { mutableLongStateOf(0L) }
    val sceneState by rememberUpdatedState(scene)
    DisposableEffect(renderer) {
        val l: (LiveEvent) -> Unit = { e -> renderer.onEvent(sceneState, e) }
        app.hub.addListener(l)
        onDispose { app.hub.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) { delay(66); tick++ } }

    val onMoveState by rememberUpdatedState(onMove)
    val onSelectState by rememberUpdatedState(onSelect)
    val selState by rememberUpdatedState(selectedId)

    var gestures = Modifier.aspectRatio(scene.width.toFloat() / scene.height)
    if (editable) {
        gestures = gestures
            .pointerInput(scene.id) {
                detectTapGestures { pos ->
                    val k = scene.width / size.width.toFloat()
                    val hit = hitTest(sceneState, pos.x * k, pos.y * k)
                    onSelectState(hit?.id)
                }
            }
            .pointerInput(scene.id) {
                var dragging: OverlayElement? = null
                var accX = 0f; var accY = 0f
                detectDragGestures(
                    onDragStart = { pos ->
                        val k = scene.width / size.width.toFloat()
                        val sel = sceneState.elements.firstOrNull { it.id == selState }
                        val hit = if (sel != null && inside(sel, pos.x * k, pos.y * k)) sel else hitTest(sceneState, pos.x * k, pos.y * k)
                        dragging = hit?.takeIf { !it.locked }
                        accX = 0f; accY = 0f
                        if (hit != null) onSelectState(hit.id)
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        val d = dragging ?: return@detectDragGestures
                        change.consume()
                        val k = scene.width / size.width.toFloat()
                        accX += amount.x * k; accY += amount.y * k
                        onMoveState(d, d.x + accX, d.y + accY)
                    },
                )
            }
    }

    Canvas(modifier.then(gestures)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val s = size.width / scene.width
        if (drawBackdrop) drawCheckerboard()
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save(); nc.scale(s, s)
            renderer.draw(nc, scene, app.hub.stats.value, app.hub.recent(60), showAlertPreview = effectiveAlertPreview)
            nc.restore()
        }
        if (editable) {
            scene.elements.firstOrNull { it.id == selectedId }?.let { el ->
                drawRect(
                    Ax.Accent, topLeft = Offset(el.x * s, el.y * s), size = Size(el.w * s, el.h * s),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
                )
                listOf(Offset(el.x, el.y), Offset(el.x + el.w, el.y), Offset(el.x, el.y + el.h), Offset(el.x + el.w, el.y + el.h))
                    .forEach { drawCircle(Color.White, 5f, Offset(it.x * s, it.y * s)) }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard() {
    drawRect(Color(0xFF0B101E))
    val cell = size.width / 18f
    var y = 0f; var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) { drawRect(Color(0xFF131C33), Offset(x, y), Size(cell, cell)); x += cell * 2 }
        y += cell; row++
    }
}

private fun inside(e: OverlayElement, x: Float, y: Float) = x >= e.x && x <= e.x + e.w && y >= e.y && y <= e.y + e.h

private fun hitTest(scene: OverlayScene, x: Float, y: Float): OverlayElement? =
    scene.elements.lastOrNull { it.visible && inside(it, x, y) }
