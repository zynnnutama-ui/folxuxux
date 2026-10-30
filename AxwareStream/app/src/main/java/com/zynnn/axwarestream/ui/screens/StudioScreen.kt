package com.zynnn.axwarestream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.events.Platform
import com.zynnn.axwarestream.core.overlay.Animation
import com.zynnn.axwarestream.core.overlay.ElementType
import com.zynnn.axwarestream.core.overlay.ImageFit
import com.zynnn.axwarestream.core.overlay.ImageFitMode
import com.zynnn.axwarestream.core.overlay.ImageStore
import com.zynnn.axwarestream.core.overlay.Orientation
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.OverlayScene
import com.zynnn.axwarestream.core.overlay.Templates
import com.zynnn.axwarestream.core.overlay.Transition
import com.zynnn.axwarestream.core.overlay.WebLayerHost
import com.zynnn.axwarestream.core.overlay.fitBoxToImage
import com.zynnn.axwarestream.core.overlay.withHeight
import com.zynnn.axwarestream.core.overlay.withWidth
import com.zynnn.axwarestream.core.server.OverlayServer
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.components.AxField
import com.zynnn.axwarestream.ui.components.ChipRow
import com.zynnn.axwarestream.ui.components.ColorPicker
import com.zynnn.axwarestream.ui.components.DropdownField
import com.zynnn.axwarestream.ui.components.OverlayPreview
import com.zynnn.axwarestream.ui.components.Pill
import com.zynnn.axwarestream.ui.components.ScreenHeader
import com.zynnn.axwarestream.ui.components.SectionCard
import com.zynnn.axwarestream.ui.components.Segmented
import com.zynnn.axwarestream.ui.components.SliderRow
import com.zynnn.axwarestream.ui.components.SwitchRow
import com.zynnn.axwarestream.ui.components.WebElementFields
import com.zynnn.axwarestream.ui.theme.Ax

private val METRICS = listOf("LIKE", "DIAMOND", "GIFT", "FOLLOW", "SHARE", "REPOST", "CHAT", "VIEWERS", "JOIN", "SUBSCRIBE", "DONATION")
private val METRIC_LABEL = mapOf("CHAT" to "Komentar", "GIFT" to "Gift", "DIAMOND" to "Diamond", "LIKE" to "Like", "FOLLOW" to "Follow",
    "SHARE" to "Share", "REPOST" to "Repost", "SUBSCRIBE" to "Subscribe", "DONATION" to "Donasi webhook (nominal)", "VIEWERS" to "Penonton", "JOIN" to "Penonton masuk")
private val TRIGGERS = listOf("ALL") + listOf(EventType.GIFT, EventType.FOLLOW, EventType.SHARE, EventType.REPOST, EventType.LIKE, EventType.SUBSCRIBE, EventType.DONATION).map { it.name }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(actions: AppActions) {
    val app = AxwareApp.instance
    val repo = app.overlays
    val project by repo.project.collectAsState()
    var editingId by rememberSaveable { mutableStateOf(project.activeSceneId) }
    val scene = project.scenes.firstOrNull { it.id == editingId } ?: project.activeScene
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = scene.elements.firstOrNull { it.id == selectedId }
    var showAdd by remember { mutableStateOf(false) }
    var showNewScene by remember { mutableStateOf(false) }
    var alertPreview by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Overlay", "Desain overlay untuk TikTok LIVE & OBS") {
            IconButton(onClick = { actions.importOverlay() }) { Icon(Icons.Rounded.FileDownload, "Impor", tint = Ax.Muted) }
            IconButton(onClick = { actions.exportOverlay() }) { Icon(Icons.Rounded.FileUpload, "Ekspor", tint = Ax.Muted) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---------------- daftar scene
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                project.scenes.forEach { s ->
                    SceneChip(s, s.id == scene.id, s.id == project.activeSceneId) { editingId = s.id; selectedId = null }
                }
                Box(
                    Modifier.size(width = 96.dp, height = 112.dp).clip(RoundedCornerShape(16.dp)).background(Ax.Surface)
                        .border(1.dp, Ax.Line, RoundedCornerShape(16.dp)).clickable { showNewScene = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Add, null, tint = Ax.Accent); Text("Scene", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                    }
                }
            }

            // ---------------- kanvas editor
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentAlignment = Alignment.Center) {
                OverlayPreview(
                    scene, Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, Ax.Line, RoundedCornerShape(16.dp)),
                    selectedId = selectedId, editable = true, showAlertPreview = alertPreview,
                    onSelect = { selectedId = it },
                    onMove = { el, x, y ->
                        val nx = x.coerceIn(-el.w / 2, scene.width - el.w / 2)
                        val ny = y.coerceIn(-el.h / 2, scene.height - el.h / 2)
                        repo.updateElement(scene.id, el.copy(x = nx, y = ny))
                    },
                )
            }
            Text("Ketuk elemen untuk memilih, geser untuk memindahkan.", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Elemen")
                }
                if (scene.id != project.activeSceneId) {
                    OutlinedButton(onClick = { repo.setActiveScene(scene.id) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tayangkan")
                    }
                } else {
                    OutlinedButton(onClick = {}, enabled = false, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) { Text("Tayang", maxLines = 1) }
                }
                OutlinedButton(onClick = { simulate(app) }, shape = RoundedCornerShape(20.dp)) { Text("Tes event") }
            }

            if (selected != null) ElementEditor(scene, selected, actions, onDeselect = { selectedId = null })
            else SceneSettings(scene, project.scenes.size, alertPreview, { alertPreview = it }, onDeleted = { editingId = project.activeSceneId })

            SociabuzzCard(scene) { selectedId = it }
            ElementList(scene, selectedId) { selectedId = it }
            OverlayUrlCard(actions, scene, selected)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAdd) AddElementDialog(onDismiss = { showAdd = false }) { type ->
        val el = OverlayElement.create(type, scene.width, scene.height)
        repo.addElement(scene.id, el); selectedId = el.id; showAdd = false
    }
    if (showNewScene) NewSceneDialog(project.templates, onDismiss = { showNewScene = false }) { s ->
        if (s == null) return@NewSceneDialog
        editingId = repo.applyTemplate(s, activate = false); selectedId = null; showNewScene = false
    }
}

fun simulate(app: AxwareApp) {
    val samples = listOf(
        LiveEvent(Platform.SYSTEM, EventType.GIFT, "Rina", "Rose", amount = 5, amountText = "x5", diamonds = 5),
        LiveEvent(Platform.SYSTEM, EventType.GIFT, "Sultan", "Finger Heart", amount = 10, amountText = "x10", diamonds = 50),
        LiveEvent(Platform.SYSTEM, EventType.FOLLOW, "Sinta", "mengikuti host"),
        LiveEvent(Platform.SYSTEM, EventType.SHARE, "Dimas", "membagikan live"),
        LiveEvent(Platform.SYSTEM, EventType.CHAT, "Budi", "Halo semua, semangat live-nya"),
        LiveEvent(Platform.SYSTEM, EventType.LIKE, "Ayu", "menyukai live", amount = 15, amountText = "x15"),
    )
    app.hub.emit(samples.random().copy(id = java.util.UUID.randomUUID().toString(), timestamp = System.currentTimeMillis()))
}

@Composable
private fun SceneChip(s: OverlayScene, editing: Boolean, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(22.dp)).background(if (editing) Ax.AccentSoft else Ax.Surface)
            .border(if (editing) 1.5.dp else 1.dp, if (editing) Ax.Accent else Ax.Line, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) {
            OverlayPreview(s, Modifier.clip(RoundedCornerShape(14.dp)))
        }
        Spacer(Modifier.height(4.dp))
        Text(s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (active) Text("● Tayang", style = MaterialTheme.typography.labelSmall, color = Ax.Live)
        else Text(if (s.orientation == Orientation.PORTRAIT) "9:16" else "16:9", style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
    }
}

@Composable
private fun SceneSettings(scene: OverlayScene, count: Int, alertPreview: Boolean, onAlertPreview: (Boolean) -> Unit, onDeleted: () -> Unit) {
    val repo = AxwareApp.instance.overlays
    var confirm by remember { mutableStateOf(false) }
    SectionCard(title = "Pengaturan scene") {
        AxField(scene.name, { v -> repo.updateScene(scene.id) { it.copy(name = v) } }, "Nama scene")
        Text("Transisi saat pindah scene", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        ChipRow(Transition.entries, scene.transition, { it.label }) { t -> repo.updateScene(scene.id) { it.copy(transition = t) } }
        Text("Orientasi kanvas", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Segmented(Orientation.entries, scene.orientation, { it.label }, { o -> repo.updateScene(scene.id) { it.copy(orientation = o) } })
        SwitchRow("Pratinjau alert", "Tampilkan contoh popup alert di kanvas", alertPreview, onAlertPreview)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { repo.duplicateScene(scene.id) }, shape = RoundedCornerShape(18.dp)) { Text("Duplikat") }
            OutlinedButton(onClick = { repo.saveAsTemplate(scene.id) }, shape = RoundedCornerShape(18.dp)) { Text("Simpan template") }
            if (count > 1) TextButton(onClick = { confirm = true }) { Text("Hapus", color = Ax.Live) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Hapus scene?") }, text = { Text("Scene \"${scene.name}\" akan dihapus permanen.") },
        confirmButton = { TextButton(onClick = { repo.deleteScene(scene.id); confirm = false; onDeleted() }) { Text("Hapus", color = Ax.Live) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
        containerColor = Ax.Surface,
    )
}

@Composable
private fun ElementEditor(scene: OverlayScene, el: OverlayElement, actions: AppActions, onDeselect: () -> Unit) {
    val repo = AxwareApp.instance.overlays
    fun up(f: (OverlayElement) -> OverlayElement) = repo.updateElement(scene.id, f(el))
    val imgSize = remember(el.media) {
        if (el.type == ElementType.IMAGE && el.media.isNotBlank()) repo.mediaFile(el.media)?.let { ImageStore.size(it) } else null
    }
    val ratio = imgSize?.let { it.first.toFloat() / it.second }
    SectionCard(title = el.name, action = { Pill(el.type.label) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ToolIcon(Icons.Rounded.ArrowUpward, "Naik") { repo.moveLayer(scene.id, el.id, 1) }
            ToolIcon(Icons.Rounded.ArrowDownward, "Turun") { repo.moveLayer(scene.id, el.id, -1) }
            ToolIcon(Icons.Rounded.ContentCopy, "Duplikat") { repo.duplicateElement(scene.id, el.id) }
            ToolIcon(if (el.visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Tampil") { up { it.copy(visible = !it.visible) } }
            ToolIcon(if (el.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "Kunci") { up { it.copy(locked = !it.locked) } }
            ToolIcon(Icons.Rounded.Delete, "Hapus", Ax.Live) { repo.removeElement(scene.id, el.id); onDeselect() }
        }
        AxField(el.name, { v -> up { it.copy(name = v) } }, "Nama layer")

        val textual = el.type in setOf(ElementType.TEXT, ElementType.RUNNING_TEXT, ElementType.DYNAMIC_TEXT, ElementType.ALERT,
            ElementType.COUNTER, ElementType.PROGRESS, ElementType.LEADERBOARD)
        if (textual) {
            AxField(el.text, { v -> up { it.copy(text = v) } }, when (el.type) {
                ElementType.COUNTER, ElementType.PROGRESS -> "Label"
                ElementType.LEADERBOARD -> "Judul"
                else -> "Teks"
            }, singleLine = false)
            if (el.type == ElementType.DYNAMIC_TEXT || el.type == ElementType.ALERT)
                Text("Token: {viewers} {like} {diamond} {gift} {follow} {share} {chat} {top_gifter} {top_user} {user} {message} {amount}",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Muted, fontFamily = FontFamily.Monospace)
        }
        when (el.type) {
            ElementType.IMAGE -> ImageElementFields(scene, el, imgSize, actions) { repo.updateElement(scene.id, it) }
            ElementType.SHAPE -> Segmented(listOf("RECT", "CIRCLE", "LINE"), el.shape,
                { mapOf("RECT" to "Kotak", "CIRCLE" to "Lingkaran", "LINE" to "Garis")[it]!! }, { v -> up { it.copy(shape = v) } })
            ElementType.COUNTER, ElementType.PROGRESS -> {
                DropdownField("Metrik", METRICS, el.metric, { METRIC_LABEL[it] ?: it }) { v -> up { it.copy(metric = v) } }
                if (el.type == ElementType.PROGRESS)
                    AxField(el.goal.toString(), { v -> v.filter(Char::isDigit).toLongOrNull()?.let { g -> up { it.copy(goal = g) } } }, "Target goal", number = true)
            }
            ElementType.LEADERBOARD -> {
                Segmented(listOf("DIAMOND", "POINTS"), if (el.metric == "POINTS") "POINTS" else "DIAMOND",
                    { if (it == "POINTS") "Interaksi" else "Top Gifter" }, { v -> up { it.copy(metric = v) } })
                SliderRow("Jumlah baris", el.maxItems.toFloat(), 1f..15f, { it.toInt().toString() }) { v -> up { it.copy(maxItems = v.toInt()) } }
            }
            ElementType.CHAT, ElementType.EVENT_FEED ->
                SliderRow("Jumlah baris", el.maxItems.toFloat(), 1f..15f, { it.toInt().toString() }) { v -> up { it.copy(maxItems = v.toInt()) } }
            ElementType.RUNNING_TEXT, ElementType.LIVE_TICKER ->
                SliderRow("Kecepatan", el.speed, 20f..400f, { "${it.toInt()} px/s" }) { v -> up { it.copy(speed = v) } }
            ElementType.WEB -> WebElementFields(el) { repo.updateElement(scene.id, it) }
            ElementType.ALERT -> {
                DropdownField("Muncul saat", TRIGGERS, el.trigger, { if (it == "ALL") "Gift, follow, share & sub" else EventType.parse(it).label }) { v -> up { it.copy(trigger = v) } }
                SliderRow("Durasi tampil", el.durationSec.toFloat(), 2f..20f, { "${it.toInt()} detik" }) { v -> up { it.copy(durationSec = v.toInt()) } }
            }
            else -> {}
        }

        val noText = el.type == ElementType.IMAGE || el.type == ElementType.SHAPE || el.type == ElementType.WEB
        if (!noText) {
            SliderRow("Ukuran huruf", el.fontSize, 12f..160f, { "${it.toInt()}" }) { v -> up { it.copy(fontSize = v) } }
            Segmented(listOf("LEFT", "CENTER", "RIGHT"), el.align, { mapOf("LEFT" to "Kiri", "CENTER" to "Tengah", "RIGHT" to "Kanan")[it]!! },
                { v -> up { it.copy(align = v) } })
            SwitchRow("Tebal", null, el.bold) { b -> up { it.copy(bold = b) } }
        }

        Text("Posisi & ukuran", style = MaterialTheme.typography.titleSmall)
        SliderRow("X", el.x, -el.w / 2..scene.width.toFloat(), { "${it.toInt()}" }) { v -> up { it.copy(x = v) } }
        SliderRow("Y", el.y, -el.h / 2..scene.height.toFloat(), { "${it.toInt()}" }) { v -> up { it.copy(y = v) } }
        SliderRow("Lebar", el.w, 20f..scene.width.toFloat() * 1.2f, { "${it.toInt()}" }) { v -> up { it.withWidth(v, ratio) } }
        SliderRow("Tinggi", el.h, 10f..scene.height.toFloat() * 1.2f, { "${it.toInt()}" }) { v -> up { it.withHeight(v, ratio) } }
        if (el.type == ElementType.IMAGE && ratio != null)
            SwitchRow("Kunci rasio gambar", "Lebar & tinggi berubah bersama mengikuti bentuk gambar", el.lockRatio) { b -> up { it.copy(lockRatio = b) } }
        SliderRow("Rotasi", el.rotation, -180f..180f, { "${it.toInt()}°" }) { v -> up { it.copy(rotation = v) } }
        SliderRow("Opasitas", el.opacity, 0f..1f, { "${(it * 100).toInt()}%" }) { v -> up { it.copy(opacity = v) } }
        SliderRow("Sudut membulat", el.cornerRadius, 0f..200f, { "${it.toInt()}" }) { v -> up { it.copy(cornerRadius = v) } }
        SliderRow("Tebal garis tepi", el.borderWidth, 0f..24f, { "${it.toInt()}" }) { v -> up { it.copy(borderWidth = v) } }

        if (el.type != ElementType.WEB) {
        Text("Warna", style = MaterialTheme.typography.titleSmall)
        if (!noText) ColorPicker("Teks", el.textColor) { c -> up { it.copy(textColor = c) } }
        ColorPicker("Latar", el.bgColor) { c -> up { it.copy(bgColor = c) } }
        if (el.type in setOf(ElementType.PROGRESS, ElementType.LEADERBOARD, ElementType.CHAT, ElementType.EVENT_FEED, ElementType.COUNTER, ElementType.ALERT))
            ColorPicker("Aksen", el.accentColor) { c -> up { it.copy(accentColor = c) } }
        ColorPicker("Garis tepi", el.borderColor) { c -> up { it.copy(borderColor = c) } }
        }

        DropdownField("Animasi", Animation.entries, el.animation, { it.label }) { v -> up { it.copy(animation = v) } }
        TextButton(onClick = onDeselect) { Text("Selesai") }
    }
}

@Composable
private fun ImageElementFields(scene: OverlayScene, el: OverlayElement, imgSize: Pair<Int, Int>?, actions: AppActions, save: (OverlayElement) -> Unit) {
    val repo = AxwareApp.instance.overlays
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            actions.pickImage { name ->
                if (name == null) { actions.toast("Gagal memuat gambar"); return@pickImage }
                val size = repo.mediaFile(name)?.let { ImageStore.size(it) }
                if (size == null) { actions.toast("File bukan gambar yang didukung"); return@pickImage }
                val base = el.copy(media = name, imgZoom = 1f, imgPanX = 0f, imgPanY = 0f)
                save(base.fitBoxToImage(size.first, size.second, scene.width.toFloat(), scene.height.toFloat()))
            }
        }, shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (el.media.isBlank()) "Pilih gambar" else "Ganti gambar")
        }
        if (imgSize != null) Text("${imgSize.first}×${imgSize.second} px", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    }
    if (el.media.isBlank()) return
    Text("Tampilan gambar", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    Segmented(ImageFitMode.entries, el.fit, { it.label }, { v -> save(el.copy(fit = v)) })
    val crop = imgSize?.let { ImageFit.croppedFraction(el.fit, el.w, el.h, it.first.toFloat(), it.second.toFloat(), el.imgZoom, el.imgPanX, el.imgPanY) } ?: 0f
    Text(
        when {
            crop > 0.005f -> "Sebagian gambar terpotong (${(crop * 100).toInt()}%). Pilih \"Utuh\", kecilkan zoom, atau tekan \"Pas dengan gambar\"."
            el.fit == ImageFitMode.CONTAIN -> "Gambar tampil utuh. Sisa ruang memakai warna Latar (transparan bila tidak diisi)."
            el.fit == ImageFitMode.STRETCH -> "Gambar direntang mengikuti kotak (bisa terlihat gepeng bila rasio berbeda)."
            else -> "Gambar mengisi penuh kotak tanpa terpotong."
        },
        style = MaterialTheme.typography.bodySmall, color = if (crop > 0.005f) Ax.Warn else Ax.Muted,
    )
    if (imgSize != null) OutlinedButton(onClick = {
        save(el.copy(imgZoom = 1f, imgPanX = 0f, imgPanY = 0f).fitBoxToImage(imgSize.first, imgSize.second, scene.width.toFloat(), scene.height.toFloat()))
    }, shape = RoundedCornerShape(18.dp)) { Text("Pas dengan gambar") }
    SliderRow("Zoom", el.imgZoom, ImageFit.ZOOM_MIN..ImageFit.ZOOM_MAX, { "${(it * 100).toInt()}%" }) { v -> save(el.copy(imgZoom = v)) }
    SliderRow("Geser horizontal", el.imgPanX, -1f..1f, { "${(it * 100).toInt()}" }) { v -> save(el.copy(imgPanX = v)) }
    SliderRow("Geser vertikal", el.imgPanY, -1f..1f, { "${(it * 100).toInt()}" }) { v -> save(el.copy(imgPanY = v)) }
    Text("Bentuk", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    Segmented(listOf("RECT", "CIRCLE"), if (el.shape == "CIRCLE") "CIRCLE" else "RECT",
        { if (it == "CIRCLE") "Lingkaran" else "Kotak" }, { v -> save(el.copy(shape = v)) })
    SwitchRow("Cerminkan", "Balik gambar kiri-kanan", el.flipH) { b -> save(el.copy(flipH = b)) }
    TextButton(onClick = { save(el.copy(fit = ImageFitMode.CONTAIN, imgZoom = 1f, imgPanX = 0f, imgPanY = 0f, flipH = false)) }) {
        Text("Reset penyesuaian gambar")
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, tint: Color = Ax.Text, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
    }
}

@Composable
private fun SociabuzzCard(scene: OverlayScene, onSelect: (String) -> Unit) {
    val repo = AxwareApp.instance.overlays
    val existing = scene.elements.filter { it.type == ElementType.WEB }
    SectionCard(title = "Alert Sociabuzz", action = { Pill(if (existing.isEmpty()) "Belum dipasang" else "${existing.size} terpasang", if (existing.isEmpty()) Ax.Muted else Ax.Ok) }) {
        Text("Tampilkan alert donasi/dukungan Sociabuzz di siaran. Halaman alert asli Sociabuzz dijalankan di dalam aplikasi, " +
            "jadi animasi & suaranya sama dengan di OBS.", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        existing.forEach { el ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ax.Surface2).clickable { onSelect(el.id) }
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(el.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(el.url.ifBlank { "Link belum diisi" }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = if (WebLayerHost.isValidUrl(el.url)) Ax.Muted else Ax.Warn)
                }
                Text("Atur", style = MaterialTheme.typography.labelMedium, color = Ax.Accent)
            }
        }
        Button(onClick = {
            val el = OverlayElement.create(ElementType.WEB, scene.width, scene.height)
            repo.addElement(scene.id, el); onSelect(el.id)
        }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tambah Alert Sociabuzz")
        }
        Text("Di siaran kamera/layar Axware, alert ditumpuk ke video (Setelan › \"Tumpuk overlay ke video siaran\"). Di Mode Overlay mengambang, " +
            "alert tampil di atas layar. Lewat URL Browser Source, alert ikut tampil di OBS/LIVE Studio.",
            style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    }
}

@Composable
private fun ElementList(scene: OverlayScene, selectedId: String?, onSelect: (String) -> Unit) {
    SectionCard(title = "Layer (${scene.elements.size})") {
        if (scene.elements.isEmpty()) Text("Belum ada elemen. Tekan + Elemen.", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        scene.elements.asReversed().forEach { el ->
            val on = el.id == selectedId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) Ax.AccentSoft else Color.Transparent)
                    .clickable { onSelect(el.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(el.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (el.visible) Ax.Text else Ax.Muted)
                if (el.locked) Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = Ax.Muted)
                if (!el.visible) Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(14.dp), tint = Ax.Muted)
                Spacer(Modifier.width(6.dp))
                Pill(el.type.label, if (el.type.dynamic) Ax.Accent else Ax.Muted)
            }
        }
    }
}

@Composable
private fun OverlayUrlCard(actions: AppActions, scene: OverlayScene, selected: OverlayElement?) {
    val app = AxwareApp.instance
    val ips = remember { OverlayServer.lanAddresses() }
    val port = app.server.port
    val base = if (ips.isEmpty() || port == 0) null else "http://${ips.first()}:$port/"
    SectionCard(title = "URL overlay (Browser Source)") {
        Text("Tempel di OBS / Streamlabs / TikTok LIVE Studio sebagai Browser Source. Perangkat harus di jaringan Wi-Fi / hotspot yang sama.",
            style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        if (base == null) {
            Text(if (port == 0) "Server overlay belum berjalan." else "Tidak ada jaringan Wi-Fi/hotspot. Sambungkan ke Wi-Fi untuk memakai URL overlay.",
                style = MaterialTheme.typography.bodySmall, color = Ax.Warn)
        } else {
            UrlRow("Semua scene (ikut scene aktif)", base, actions)
            UrlRow("Hanya scene ini", "${base}?scene=${scene.id}", actions)
            if (selected != null) UrlRow("Hanya elemen \"${selected.name}\"", "${base}?el=${selected.id}", actions)
            UrlRow("Webhook event (POST JSON)", "${base}api/event", actions)
        }
    }
}

@Composable
private fun UrlRow(label: String, url: String, actions: AppActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
            Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { actions.copy("URL overlay", url); actions.toast("URL disalin") }) { Icon(Icons.Rounded.ContentCopy, "Salin", tint = Ax.Accent) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddElementDialog(onDismiss: () -> Unit, onPick: (ElementType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ax.Surface,
        title = { Text("Tambah elemen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Statis", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElementType.entries.filter { !it.dynamic }.forEach { t -> PickChip(t.label) { onPick(t) } }
                }
                Text("Widget interaktif (data live)", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ElementType.entries.filter { it.dynamic }.forEach { t -> PickChip(t.label) { onPick(t) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun PickChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Ax.Surface2).border(1.dp, Ax.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewSceneDialog(userTemplates: List<OverlayScene>, onDismiss: () -> Unit, onPick: (OverlayScene?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ax.Surface,
        title = { Text("Scene baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kosong", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickChip("Portrait 9:16") { onPick(OverlayScene(name = "Scene baru", orientation = Orientation.PORTRAIT)) }
                    PickChip("Landscape 16:9") { onPick(OverlayScene(name = "Scene baru", orientation = Orientation.LANDSCAPE)) }
                }
                Text("Template bawaan", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Templates.builtIn().forEach { t -> PickChip(t.name) { onPick(t) } }
                }
                if (userTemplates.isNotEmpty()) {
                    Text("Template saya", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        userTemplates.forEach { t -> PickChip(t.name) { onPick(t) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
