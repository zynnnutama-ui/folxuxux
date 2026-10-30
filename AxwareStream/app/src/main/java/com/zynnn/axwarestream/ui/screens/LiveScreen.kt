package com.zynnn.axwarestream.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.MainActions
import com.zynnn.axwarestream.core.BudgetManager
import com.zynnn.axwarestream.core.tiktok.TikTokLiveClient
import com.zynnn.axwarestream.model.LiveState
import com.zynnn.axwarestream.model.StreamHealth
import com.zynnn.axwarestream.model.StreamSettings
import com.zynnn.axwarestream.model.VideoSourceKind
import com.zynnn.axwarestream.service.FloatingOverlayService
import com.zynnn.axwarestream.service.StreamForegroundService
import com.zynnn.axwarestream.ui.components.*
import com.zynnn.axwarestream.ui.theme.Ax

private const val TIKTOK_PKG = "com.zhiliaoapp.musically"
private const val TIKTOK_ALT_PKG = "com.ss.android.ugc.trill"

@Composable
fun LiveScreen(app: AxwareApp, service: StreamForegroundService?, actions: MainActions) {
    val settings by app.settings.settings.collectAsState()
    val scenes by app.scenes.scenes.collectAsState()
    val activeId by app.scenes.activeId.collectAsState()
    val live by (service?.liveState ?: kotlinx.coroutines.flow.MutableStateFlow(LiveState())).collectAsState()
    val acc by app.tiktok.account.collectAsState()
    val tt by app.tiktok.state.collectAsState()
    val floatRunning by FloatingOverlayService.running.collectAsState()
    val alertEvents by app.bus.alerts.collectAsState()
    val latestAlert = alertEvents.lastOrNull()

    var keyDialog by remember { mutableStateOf(false) }
    var viaApp by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activeId, scenes) { service?.refreshActiveScene() }

    val readyRtmp = settings.rtmpUrl.isNotBlank() && settings.streamKey.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Axware Stream",
            subtitle = if (viaApp) "Mode Overlay · Siaran lewat aplikasi TikTok"
                       else "${settings.preset.label} · ${settings.fps}fps · ${settings.videoBitrateKbps} kbps",
            brand = true,
            trailing = {
                when {
                    live.streaming -> Pill("● LIVE  ${formatDur(live.durationSec)}", color = Ax.Live, filled = true)
                    floatRunning   -> Pill("● Overlay Aktif", color = Ax.Ok, filled = true)
                    readyRtmp      -> Pill("Siap Live", color = Ax.Cyan)
                    else           -> Pill("Offline", color = Ax.Muted)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Kartu Akun TikTok + Stream Key terpadu
            TikTokAccountCard(
                app = app,
                settings = settings,
                viaApp = viaApp,
                streaming = live.streaming,
                onOpenKeyDialog = { keyDialog = true }
            )

            // 2. Pilihan metode siaran
            Segmented(
                options = listOf(false, true),
                selected = viaApp,
                label = { if (it) "Lewat aplikasi TikTok" else "Siaran RTMP" },
                onSelect = { viaApp = it }
            )

            if (!viaApp) {
                Segmented(
                    options = VideoSourceKind.entries,
                    selected = settings.source,
                    label = { it.label },
                    onSelect = { s ->
                        if (!live.streaming) {
                            app.settings.update { it.copy(source = s) }
                            service?.switchSource(s)
                        }
                    }
                )

                if (settings.source == VideoSourceKind.CAMERA) {
                    CameraStage(
                        service = service,
                        settings = settings,
                        live = live,
                        activeSceneName = scenes.firstOrNull { it.id == activeId }?.name ?: "Default",
                        onFlip = {
                            val next = !settings.frontCamera
                            app.settings.update { it.copy(frontCamera = next) }
                            service?.flipCamera(next)
                        },
                        onToggleMic = {
                            val next = !settings.micMuted
                            app.settings.update { it.copy(micMuted = next) }
                            service?.setMicMuted(next)
                        },
                        onTogglePreview = {
                            if (live.previewing) service?.stopCameraPreview()
                            else actions.ensurePermissionsAndRun { service?.startCameraPreview() }
                        }
                    )
                } else {
                    ScreenCaptureCard(
                        app = app,
                        streaming = live.streaming,
                        activeSceneName = scenes.firstOrNull { it.id == activeId }?.name ?: "Default"
                    )
                }

                // Scene switcher cepat
                if (scenes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        scenes.forEach { sc ->
                            val sel = sc.id == activeId
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) Ax.AccentSoft else Ax.Surface)
                                    .border(1.dp, if (sel) Ax.Accent else Ax.Line, RoundedCornerShape(50))
                                    .clickable { app.scenes.setActive(sc.id) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(sc.name, style = MaterialTheme.typography.labelLarge, color = if (sel) Ax.Accent else Ax.Muted)
                            }
                        }
                    }
                }

                // Tombol CTA Utama: Mulai / Akhiri Live
                GoLiveButton(
                    streaming = live.streaming,
                    onClick = {
                        if (live.streaming) {
                            service?.stopStreaming()
                        } else {
                            if (acc != null) app.tiktok.reconnect()
                            if (!readyRtmp) {
                                keyDialog = true
                            } else {
                                actions.ensurePermissionsAndRun {
                                    if (settings.source == VideoSourceKind.SCREEN) {
                                        actions.requestMediaProjection { code, data ->
                                            service?.setMediaProjection(code, data)
                                            service?.startStreaming()
                                        }
                                    } else {
                                        service?.startStreaming()
                                    }
                                }
                            }
                        }
                    }
                )

                if (live.error != null) {
                    AxCard(accentBorder = Ax.Live.copy(alpha = 0.6f), padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Ax.Live, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(live.error!!, style = MaterialTheme.typography.bodySmall, color = Ax.Live, modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (live.streaming) {
                    Text(
                        "Setelah video tersambung, selesaikan dengan menekan Go LIVE di TikTok LIVE Producer. Akhiri live di aplikasi ini dan di TikTok.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ax.Muted
                    )
                }

                // Mixer audio & metrik hanya tampil ketika relevan
                AudioCard(app, settings, live, service)
                if (live.streaming) {
                    TelemetryRow(live, settings)
                }
            } else {
                ViaTikTokAppSection(
                    app = app,
                    floatRunning = floatRunning,
                    ttConnected = tt.phase == TikTokLiveClient.Phase.LIVE || tt.phase == TikTokLiveClient.Phase.WAITING_LIVE || tt.phase == TikTokLiveClient.Phase.CONNECTING,
                    actions = actions
                )
            }

            // Komentar & gift real-time dari akun TikTok yang dihubungkan
            LiveCommentsCard(app = app, maxHeight = 260.dp)

            // Pratinjau alert terintegrasi
            AxCard(padding = 14.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pratinjau Alert", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
                    TextButton(
                        onClick = { app.testAlert() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = Ax.Accent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tes Alert", style = MaterialTheme.typography.labelMedium, color = Ax.Accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                AlertBannerPreview(latest = latestAlert)
                Spacer(Modifier.height(8.dp))
                OverlayPreview(app = app, height = 160.dp)
            }

            if (!viaApp) {
                BudgetCard(app, live)
            }
        }
    }

    if (keyDialog) {
        StreamKeyDialog(
            initialUrl = settings.rtmpUrl,
            initialKey = settings.streamKey,
            onDismiss = { keyDialog = false },
            onSave = { url, key ->
                app.settings.update { it.copy(rtmpUrl = url, streamKey = key) }
                keyDialog = false
            }
        )
    }
}

@Composable
private fun CameraStage(
    service: StreamForegroundService?,
    settings: StreamSettings,
    live: LiveState,
    activeSceneName: String,
    onFlip: () -> Unit,
    onToggleMic: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Ax.Surface)
            .border(1.dp, if (live.streaming) Ax.Live.copy(alpha = 0.6f) else Ax.Line, RoundedCornerShape(28.dp))
    ) {
        if (service != null && (live.previewing || live.streaming)) {
            AndroidView(factory = { service.getOrCreateCameraView() }, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Ax.Muted, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(6.dp))
                Text("Pratinjau kamera nonaktif", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onTogglePreview, shape = RoundedCornerShape(50)) {
                    Text("Aktifkan Kamera")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill("Scene · $activeSceneName", color = Ax.Cyan)
            if (live.streaming) Pill("${live.fpsActual} fps · ${live.bitrateActualKbps} kbps", color = Ax.Ok)
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircleIconButton(Icons.Rounded.Cameraswitch, "Balik kamera", onClick = onFlip, active = settings.frontCamera)
            CircleIconButton(
                if (settings.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                "Bisu mikrofon",
                onClick = onToggleMic,
                danger = settings.micMuted
            )
            if (!live.streaming) {
                CircleIconButton(
                    if (live.previewing) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    "Pratinjau",
                    onClick = onTogglePreview,
                    active = live.previewing
                )
            }
        }
    }
}

@Composable
private fun ScreenCaptureCard(app: AxwareApp, streaming: Boolean, activeSceneName: String) {
    val settings by app.settings.settings.collectAsState()
    val layout = settings.screenLayout
    var showEditor by remember { mutableStateOf(false) }

    AxCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ax.AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ScreenShare, contentDescription = null, tint = Ax.Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tangkap Layar + Audio Game", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
                Text(
                    "Scene: $activeSceneName · Game ${layout.orientation.label} · ${layout.fit.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ax.Muted
                )
            }
            FilledTonalButton(
                onClick = { showEditor = true },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Ax.Surface2, contentColor = Ax.Cyan),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Rounded.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Tata Letak", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Ax.Surface2)
                .clickable { showEditor = true }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            ScreenLayoutPreview(app = app, maxHeight = 160.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Skala ${(layout.scale * 100).toInt()}% · Posisi (${(layout.offsetX * 100).toInt()}%, ${(layout.offsetY * 100).toInt()}%) · Latar ${layout.background.label}",
                style = MaterialTheme.typography.labelSmall,
                color = Ax.Muted
            )
            Text(
                "Ketuk pratinjau untuk geser/zoom",
                style = MaterialTheme.typography.labelSmall,
                color = Ax.Cyan
            )
        }
    }

    if (showEditor) {
        ScreenLayoutDialog(app = app, live = streaming, onDismiss = { showEditor = false })
    }
}

@Composable
private fun GoLiveButton(streaming: Boolean, onClick: () -> Unit) {
    val brush = if (streaming) Brush.linearGradient(listOf(Ax.Live, Color(0xFFB91C1C))) else Ax.Brand
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .border(1.dp, if (streaming) Ax.Line else Color.Transparent, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (streaming) {
                StatusDot(Color.White, 10.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (streaming) "Akhiri Live" else "Mulai Live TikTok",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ViaTikTokAppSection(
    app: AxwareApp,
    floatRunning: Boolean,
    ttConnected: Boolean,
    actions: MainActions,
) {
    val ctx = LocalContext.current
    AxCard(accentBorder = if (floatRunning) Ax.Ok.copy(alpha = 0.55f) else Ax.Line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (floatRunning) Ax.Ok.copy(alpha = 0.18f) else Ax.AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PictureInPictureAlt,
                    contentDescription = null,
                    tint = if (floatRunning) Ax.Ok else Ax.Accent
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Mode Overlay Mengambang", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
                Text(
                    if (floatRunning) "Overlay tampil di atas TikTok / game · ketuk pil untuk sembunyikan atau tutup"
                    else "Menampilkan alert gift, follow & komentar mengambang di atas aplikasi TikTok",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ax.Muted
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        StepLine("1", "Hubungkan @username TikTok di kartu atas", done = ttConnected)
        StepLine("2", "Aktifkan Overlay Mengambang di bawah", done = floatRunning)
        StepLine("3", "Buka aplikasi TikTok › LIVE dan pilih siaran layar (Mobile Gaming) agar layar HP ikut tersiar", done = false)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (floatRunning) actions.stopFloatingOverlay()
                    else actions.startFloatingOverlay()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (floatRunning) Ax.Live else Ax.Accent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(
                    if (floatRunning) Icons.Rounded.Stop else Icons.Rounded.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (floatRunning) "Matikan Overlay" else "Aktifkan Overlay", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = {
                    val pm = ctx.packageManager
                    val launch = pm.getLaunchIntentForPackage(TIKTOK_PKG)
                        ?: pm.getLaunchIntentForPackage(TIKTOK_ALT_PKG)
                    if (launch != null) {
                        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Buka TikTok")
            }
        }
    }
}

@Composable
private fun StepLine(num: String, text: String, done: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (done) Ax.Ok.copy(alpha = 0.22f) else Ax.Surface2)
                .border(1.dp, if (done) Ax.Ok else Ax.Line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Ax.Ok, modifier = Modifier.size(13.dp))
            } else {
                Text(num, style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (done) Ax.Text else Ax.Muted)
    }
}

@Composable
private fun TelemetryRow(live: LiveState, settings: StreamSettings) {
    val healthCol = when (live.health) {
        StreamHealth.EXCELLENT, StreamHealth.GOOD -> Ax.Ok
        StreamHealth.FAIR -> Ax.Warn
        StreamHealth.POOR -> Ax.Live
        StreamHealth.IDLE -> Ax.Muted
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Bitrate", "${live.bitrateActualKbps} kbps", "target ${settings.videoBitrateKbps}", accent = Ax.Cyan, modifier = Modifier.weight(1f))
        StatTile("FPS", "${live.fpsActual}", "drop ${live.droppedFrames}", accent = Ax.Text, modifier = Modifier.weight(1f))
        StatTile("Koneksi", live.health.label, if (live.reconnecting) "menyambung…" else "RTMP", accent = healthCol, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AudioCard(app: AxwareApp, settings: StreamSettings, live: LiveState, service: StreamForegroundService?) {
    AxCard(padding = 14.dp) {
        SectionLabel("Audio", if (settings.micMuted) "Mikrofon bisu" else "Aktif")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (settings.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = null,
                tint = if (settings.micMuted) Ax.Live else Ax.Ok,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Mikrofon", style = MaterialTheme.typography.bodyMedium, color = Ax.Text, modifier = Modifier.width(86.dp))
            VuBar(live.micLevel, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("${(settings.micVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Ax.Muted, modifier = Modifier.width(38.dp))
        }
        Slider(
            value = settings.micVolume,
            onValueChange = { v ->
                app.settings.update { it.copy(micVolume = v) }
                service?.setMicVolume(v)
            },
            valueRange = 0f..1.5f,
            colors = SliderDefaults.colors(thumbColor = Ax.Accent, activeTrackColor = Ax.Accent, inactiveTrackColor = Ax.Surface2)
        )
        if (settings.source == VideoSourceKind.SCREEN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = Ax.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Audio Internal", style = MaterialTheme.typography.bodyMedium, color = Ax.Text, modifier = Modifier.width(86.dp))
                VuBar(live.internalLevel, modifier = Modifier.weight(1f), color = Ax.Cyan)
                Spacer(Modifier.width(8.dp))
                Text("${(settings.internalVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Ax.Muted, modifier = Modifier.width(38.dp))
            }
            Slider(
                value = settings.internalVolume,
                onValueChange = { v ->
                    app.settings.update { it.copy(internalVolume = v) }
                    service?.setInternalVolume(v)
                },
                valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(thumbColor = Ax.Cyan, activeTrackColor = Ax.Cyan, inactiveTrackColor = Ax.Surface2)
            )
        }
    }
}

@Composable
private fun BudgetCard(app: AxwareApp, live: LiveState) {
    val snap by app.budget.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    AxCard(padding = 14.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = Ax.Ok, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Kesehatan Perangkat", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CPU ${snap.cpuPercent.toInt()}/${BudgetManager.CPU_BUDGET_PERCENT.toInt()}% · ${snap.pssMb}/${BudgetManager.RAM_BUDGET_MB} MB · ${"%.1f".format(snap.batteryTempC)}°C",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ax.Muted
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Ax.Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RingGauge(
                    progress = (snap.cpuPercent / BudgetManager.CPU_BUDGET_PERCENT).coerceIn(0f, 1.5f),
                    label = "CPU ≤30%",
                    value = "${snap.cpuPercent.toInt()}%",
                    color = if (snap.cpuOverBudget) Ax.Warn else Ax.Ok
                )
                RingGauge(
                    progress = (snap.pssMb.toFloat() / BudgetManager.RAM_BUDGET_MB).coerceIn(0f, 1.5f),
                    label = "RAM ≤500M",
                    value = "${snap.pssMb}M",
                    color = if (snap.ramOverBudget) Ax.Warn else Ax.Cyan
                )
                RingGauge(
                    progress = ((snap.batteryTempC - 25f) / 25f).coerceIn(0f, 1f),
                    label = "Suhu",
                    value = "${"%.0f".format(snap.batteryTempC)}°C",
                    color = if (snap.batteryTempC >= 43f) Ax.Live else Ax.Ok
                )
            }
        }
    }
}

private fun formatDur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
