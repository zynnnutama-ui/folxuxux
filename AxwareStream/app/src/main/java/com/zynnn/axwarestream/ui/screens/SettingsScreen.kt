package com.zynnn.axwarestream.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.BuildConfig
import com.zynnn.axwarestream.core.stream.AudioSourceKind
import com.zynnn.axwarestream.core.stream.Resolution
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.components.AudioTestCard
import com.zynnn.axwarestream.ui.components.AxField
import com.zynnn.axwarestream.ui.components.BrandMark
import com.zynnn.axwarestream.ui.components.ChipRow
import com.zynnn.axwarestream.ui.components.DropdownField
import com.zynnn.axwarestream.ui.components.LIVE_PRODUCER_URL
import com.zynnn.axwarestream.ui.components.ScreenHeader
import com.zynnn.axwarestream.ui.components.SectionCard
import com.zynnn.axwarestream.ui.components.Segmented
import com.zynnn.axwarestream.ui.components.SliderRow
import com.zynnn.axwarestream.ui.components.StreamKeyDialog
import com.zynnn.axwarestream.ui.components.SwitchRow
import com.zynnn.axwarestream.ui.components.UsernameDialog
import com.zynnn.axwarestream.ui.theme.Ax

const val TIKTOK_DEV = "zynnn_173"
const val TIKTOK_URL = "https://www.tiktok.com/@zynnn_173"

@Composable
fun SettingsScreen(actions: AppActions) {
    val app = AxwareApp.instance
    val s by app.settings.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Setelan", "Akun TikTok, kualitas siaran & server lokal")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountCard(actions)
            SectionCard(title = "Video", subtitle = "Disarankan portrait 9:16 untuk TikTok") {
                Segmented(listOf(true, false), s.portrait, { if (it) "Portrait 9:16" else "Landscape 16:9" }, { v -> app.settings.update { it.copy(portrait = v) } })
                Text("Resolusi", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
                ChipRow(Resolution.entries, s.resolution, { it.label }) { r ->
                    app.settings.update { it.copy(resolution = r, videoKbps = r.defaultKbps) }
                }
                Text("Frame rate", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
                ChipRow(listOf(30, 60), s.fps, { "$it fps" }) { f -> app.settings.update { it.copy(fps = f) } }
                SliderRow("Bitrate video", s.videoKbps.toFloat(), 800f..6000f, { "${it.toInt()} kbps" }) { v ->
                    app.settings.update { it.copy(videoKbps = (v / 100).toInt() * 100) }
                }
                Text("Codec H.264 + AAC (format yang diterima TikTok LIVE). TikTok menyarankan sekitar 1.800 kbps; " +
                    "2.000–3.000 kbps aman untuk kebanyakan koneksi. Keyframe tiap 2 detik.",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
            }
            SectionCard(title = "Audio") {
                DropdownField("Sumber audio (mode layar)", AudioSourceKind.entries, s.audio, { it.label }) { a -> app.settings.update { it.copy(audio = a) } }
                if (Build.VERSION.SDK_INT < 29) Text("Audio internal butuh Android 10 ke atas. Perangkat ini akan memakai mikrofon.",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Warn)
                else Text("Audio internal hanya merekam aplikasi yang mengizinkan (sebagian besar game mengizinkan). Mode kamera selalu memakai mikrofon.",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
                SliderRow("Volume mikrofon", s.micVolume, 0f..2f, { "${(it * 100).toInt()}%" }) { v ->
                    app.settings.update { it.copy(micVolume = (v * 100).toInt() / 100f) }
                }
                SliderRow("Volume audio internal", s.internalVolume, 0f..2f, { "${(it * 100).toInt()}%" }) { v ->
                    app.settings.update { it.copy(internalVolume = (v * 100).toInt() / 100f) }
                }
                ChipRow(listOf(64, 96, 128, 160, 192), s.audioKbps, { "$it kbps" }) { a -> app.settings.update { it.copy(audioKbps = a) } }
            }
            AudioTestCard(actions)
            SectionCard(title = "Siaran") {
                SwitchRow("Tumpuk overlay ke video siaran", "Scene aktif (termasuk Alert Sociabuzz) digambar langsung di video kamera / layar yang dikirim", s.overlayInStream) { v ->
                    app.settings.update { it.copy(overlayInStream = v) }
                }
                SwitchRow("Rekam otomatis saat live", "Disimpan ke Galeri › Movies/AxwareStream", s.recordLocal) { v -> app.settings.update { it.copy(recordLocal = v) } }
            }
            ServerCard(actions)
            AboutCard(actions)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServerCard(actions: AppActions) {
    val app = AxwareApp.instance
    val s by app.settings.settings.collectAsState()
    var port by remember { mutableStateOf(s.serverPort.toString()) }
    var token by remember { mutableStateOf(s.webhookToken) }
    var running by remember { mutableStateOf(app.server.port) }
    SectionCard(title = "Server overlay & webhook lokal") {
        Text(if (running > 0) "Berjalan di port $running" else "Tidak berjalan", style = MaterialTheme.typography.bodySmall,
            color = if (running > 0) Ax.Ok else Ax.Live)
        AxField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", number = true)
        AxField(token, { token = it.trim() }, "Token webhook (opsional)", placeholder = "kosongkan jika tanpa token")
        Text("Kirim event dengan POST JSON ke /api/event, mis. {\"type\":\"DONATION\",\"user\":\"Budi\",\"amount\":50000,\"message\":\"Semangat\"}. " +
            "Jika token diisi, tambahkan ?token=… atau header X-Axware-Token.", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Button(onClick = {
            val p = port.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return@Button actions.toast("Port harus 1024–65535")
            app.settings.update { it.copy(serverPort = p, webhookToken = token) }
            app.server.webhookToken = token
            app.server.stop()
            running = runCatching { app.server.start(p) }.getOrDefault(0)
            actions.toast(if (running == p) "Server berjalan di port $p" else if (running > 0) "Port $p dipakai, memakai $running" else "Server gagal dijalankan")
        }, shape = RoundedCornerShape(18.dp)) { Text("Terapkan & mulai ulang") }
    }
}

@Composable
private fun AccountCard(actions: AppActions) {
    val app = AxwareApp.instance
    val acc by app.settings.tiktok.collectAsState()
    var askUser by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(acc.signApiKey) }
    SectionCard(title = "Akun TikTok") {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { askUser = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Username", style = MaterialTheme.typography.bodyMedium, color = Ax.Muted, modifier = Modifier.weight(1f))
            Text(if (acc.username.isBlank()) "Atur" else "@${acc.username}", style = MaterialTheme.typography.bodyMedium,
                color = Ax.Accent, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { keyDialog = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Server URL & Stream Key", style = MaterialTheme.typography.bodyMedium, color = Ax.Muted, modifier = Modifier.weight(1f))
            Text(if (acc.hasStreamKey) "Siap" else "Belum diatur", style = MaterialTheme.typography.bodyMedium,
                color = if (acc.hasStreamKey) Ax.Ok else Ax.Accent, fontWeight = FontWeight.SemiBold)
        }
        SwitchRow("Sambung event otomatis", "Baca komentar & gift saat aplikasi dibuka dan saat mulai live", acc.autoConnect) { v ->
            app.settings.updateTikTok { it.copy(autoConnect = v) }
        }
        AxField(apiKey, { apiKey = it.trim() }, "API key Euler Stream (opsional)", visual = PasswordVisualTransformation(),
            placeholder = "kosongkan untuk mode gratis")
        Text("Koneksi event memerlukan tanda tangan dari server Euler Stream (dipakai juga oleh pustaka open-source TikTokLive). " +
            "Mode gratis punya batas pemakaian; isi API key milikmu bila sering terkena batas.",
            style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                app.settings.updateTikTok { it.copy(signApiKey = apiKey) }; actions.toast("API key disimpan")
            }, shape = RoundedCornerShape(18.dp)) { Text("Simpan API key") }
            TextButton(onClick = { actions.openUrl(LIVE_PRODUCER_URL) }) { Text("LIVE Producer") }
        }
    }
    if (askUser) UsernameDialog(acc.username, onDismiss = { askUser = false }) { u ->
        askUser = false
        app.settings.updateTikTok { it.copy(username = u) }
        app.tiktok.connect(u)
    }
    if (keyDialog) StreamKeyDialog(actions, onDismiss = { keyDialog = false })
}

@Composable
private fun AboutCard(actions: AppActions) {
    SectionCard(title = "Tentang") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(56)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Axware Stream", style = MaterialTheme.typography.titleMedium)
                Text("Live streaming khusus TikTok · Versi ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
            }
        }
        InfoRow("Developer", "Zynnn")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { actions.openUrl(TIKTOK_URL) }.padding(vertical = 6.dp)) {
            Text("TikTok developer", style = MaterialTheme.typography.bodyMedium, color = Ax.Muted, modifier = Modifier.weight(1f))
            Text("@$TIKTOK_DEV", style = MaterialTheme.typography.bodyMedium, color = Ax.Accent, fontWeight = FontWeight.SemiBold)
        }
        InfoRow("Package", BuildConfig.APPLICATION_ID)
        Text("Stream key, overlay & setelan disimpan lokal di perangkat. Video dikirim langsung ke server RTMP TikTok. " +
            "Untuk membaca event live, aplikasi menghubungi TikTok dan server tanda tangan Euler Stream (hanya ID room, tanpa login).",
            style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = Ax.Muted, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}
