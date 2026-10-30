package com.zynnn.axwarestream.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.core.tiktok.TikTokLiveClient.Phase
import com.zynnn.axwarestream.model.StreamSettings
import com.zynnn.axwarestream.model.TikTokAccount
import com.zynnn.axwarestream.ui.theme.Ax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private const val LIVE_PRODUCER_URL = "https://livecenter.tiktok.com/live_producer"

@Composable
fun TikTokAccountCard(
    app: AxwareApp,
    settings: StreamSettings,
    viaApp: Boolean,
    streaming: Boolean,
    onOpenKeyDialog: () -> Unit,
) {
    val account by app.tiktok.account.collectAsState()
    val st by app.tiktok.state.collectAsState()
    var showConnect by remember { mutableStateOf(false) }

    val borderColor = when {
        st.phase == Phase.LIVE -> Ax.Ok.copy(alpha = 0.55f)
        st.phase == Phase.ERROR -> Ax.Live.copy(alpha = 0.55f)
        else -> Ax.Line.copy(alpha = 0.55f)
    }

    AxCard(padding = 0.dp, accentBorder = borderColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TikTokAvatar(account = account, live = st.phase == Phase.LIVE, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (account == null) {
                    Text("Akun TikTok", style = MaterialTheme.typography.titleSmall, color = Ax.Text)
                    Text(
                        "Ketuk Hubungkan · cukup masukkan @username",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ax.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            account!!.nickname.ifBlank { "@${account!!.username}" },
                            style = MaterialTheme.typography.titleSmall,
                            color = Ax.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        PhaseBadge(st.phase, st.viewerCount)
                    }
                    Text(
                        buildString {
                            append("@${account!!.username}")
                            if (account!!.followerCount > 0) append(" · ${compactNumber(account!!.followerCount)} pengikut")
                            if (st.title.isNotBlank()) append(" · ${st.title}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Ax.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (account == null) {
                Button(
                    onClick = { showConnect = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Ax.Accent, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Hubungkan", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { app.tiktok.reconnect() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Segarkan", tint = Ax.Muted, modifier = Modifier.size(18.dp))
                    }
                    TextButton(
                        onClick = { app.tiktok.disconnect() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Putuskan", style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
                    }
                }
            }
        }

        if (account != null && st.detail.isNotBlank() && st.phase != Phase.LIVE) {
            Text(
                st.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (st.phase == Phase.ERROR) Ax.Live else Ax.Muted,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
            )
        }

        if (account != null && (st.phase == Phase.LIVE || st.totalComments > 0 || st.totalGiftsDiamonds > 0)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniCounter("Penonton", compactNumber(st.viewerCount), Ax.Cyan, Modifier.weight(1f))
                MiniCounter("Like", compactNumber(st.likeCount), Ax.Accent, Modifier.weight(1f))
                MiniCounter("Komentar", compactNumber(st.totalComments), Ax.Text, Modifier.weight(1f))
                MiniCounter("Diamond", compactNumber(st.totalGiftsDiamonds), Ax.Gold, Modifier.weight(1f))
            }
        }

        if (!viaApp) {
            HorizontalDivider(color = Ax.Line.copy(alpha = 0.55f))
            val ready = settings.rtmpUrl.isNotBlank() && settings.streamKey.isNotBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !streaming) { onOpenKeyDialog() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (ready) Ax.CyanSoft else Ax.Surface2),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = if (ready) Ax.Cyan else Ax.Muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Server URL & Stream Key", style = MaterialTheme.typography.labelLarge, color = Ax.Text)
                    Text(
                        if (ready) "Siap · ${hostFromRtmp(settings.rtmpUrl)} · ${maskKey(settings.streamKey)}"
                        else "Belum diatur · dari TikTok LIVE Producer",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ready) Ax.Ok else Ax.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (ready) "Ubah" else "Atur",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (streaming) Ax.Muted else Ax.Cyan
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Ax.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showConnect) {
        ConnectUsernameDialog(
            initial = account?.username.orEmpty(),
            onDismiss = { showConnect = false },
            onConfirm = { u ->
                showConnect = false
                app.tiktok.connectUsername(u)
            }
        )
    }
}

@Composable
private fun PhaseBadge(phase: Phase, viewers: Int) {
    val (label, col) = when (phase) {
        Phase.LIVE          -> "LIVE · ${compactNumber(viewers)}" to Ax.Live
        Phase.CONNECTING    -> "Menyambung…" to Ax.Cyan
        Phase.WAITING_LIVE  -> "Siaga" to Ax.Gold
        Phase.RECONNECTING  -> "Ulang…" to Ax.Warn
        Phase.ENDED         -> "Selesai" to Ax.Muted
        Phase.ERROR         -> "Gangguan" to Ax.Live
        Phase.IDLE          -> "Terhubung" to Ax.Ok
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(col.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = col)
    }
}

@Composable
private fun MiniCounter(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ax.Surface2)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ax.Muted, maxLines = 1)
    }
}

@Composable
private fun ConnectUsernameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var u by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ax.Surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Hubungkan Akun TikTok", color = Ax.Text, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tidak perlu login. Jika belum live, aplikasi menunggu dan tersambung otomatis saat live dimulai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ax.Muted
                )
                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it },
                    label = { Text("Username TikTok") },
                    placeholder = { Text("contoh: @kreator_id") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (u.trim().trimStart('@').isNotBlank()) onConfirm(u) },
                enabled = u.trim().trimStart('@').isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ax.Accent, contentColor = Color.White)
            ) { Text("Hubungkan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(18.dp)) { Text("Batal", color = Ax.Muted) }
        }
    )
}

@Composable
fun StreamKeyDialog(
    initialUrl: String,
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }

    val trimmedUrl = url.trim()
    val trimmedKey = key.trim()
    val urlOk = trimmedUrl.isEmpty() || trimmedUrl.startsWith("rtmp://", true) || trimmedUrl.startsWith("rtmps://", true)
    val keyHasSpaces = trimmedKey.any { it.isWhitespace() }
    val canSave = urlOk && !keyHasSpaces

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ax.Surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Server URL & Stream Key", color = Ax.Text, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Buka TikTok LIVE Producer di browser, mulai sesi LIVE, lalu salin Server URL dan Stream Key ke sini. Kunci berlaku untuk satu sesi, jadi perbarui setiap kali live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ax.Muted
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LIVE_PRODUCER_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buka TikTok LIVE Producer")
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("rtmps://push.tiktok.com/live/") },
                    singleLine = true,
                    isError = !urlOk,
                    supportingText = if (!urlOk) ({ Text("Harus diawali rtmp:// atau rtmps://") }) else null,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Stream Key") },
                    placeholder = { Text("stream-xxxx?expire=…") },
                    singleLine = true,
                    isError = keyHasSpaces,
                    supportingText = if (keyHasSpaces) ({ Text("Stream key mengandung spasi. Salin ulang dari LIVE Producer.") }) else null,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(18.dp),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(trimmedUrl, trimmedKey) },
                enabled = canSave,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ax.Accent, contentColor = Color.White)
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(18.dp)) { Text("Batal", color = Ax.Muted) }
        }
    )
}

@Composable
fun TikTokAvatar(account: TikTokAccount?, live: Boolean, size: Dp = 44.dp) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = account?.avatarUrl) {
        val u = account?.avatarUrl.orEmpty()
        value = if (u.startsWith("http")) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(u).openConnection()).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    conn.getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }.getOrNull()
            }
        } else null
    }

    val ring = if (live) Brush.linearGradient(listOf(Ax.Accent, Ax.Cyan))
               else Brush.linearGradient(listOf(Ax.Line, Ax.Line))

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (live) 2.dp else 1.dp, ring, CircleShape)
            .background(Ax.Surface2),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp!!,
                contentDescription = account?.nickname,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            val ch = account?.nickname?.firstOrNull()?.uppercase()
                ?: account?.username?.firstOrNull()?.uppercase()
                ?: "T"
            Text(ch, style = MaterialTheme.typography.titleMedium, color = if (account != null) Ax.Cyan else Ax.Muted)
        }
    }
}

private fun hostFromRtmp(url: String): String =
    runCatching { Uri.parse(url.trim()).host ?: "RTMP" }.getOrNull() ?: "RTMP"

private fun maskKey(key: String): String {
    val t = key.trim()
    if (t.length <= 6) return "••••"
    return t.take(4) + "•••" + t.takeLast(3)
}

fun compactNumber(n: Int): String = compactNumber(n.toLong())
fun compactNumber(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000     -> String.format("%.1fK", n / 1_000.0)
    else           -> n.toString()
}
