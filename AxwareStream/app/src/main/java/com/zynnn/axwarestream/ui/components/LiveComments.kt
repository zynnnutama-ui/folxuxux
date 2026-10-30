package com.zynnn.axwarestream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.AxwareApp
import com.zynnn.axwarestream.core.events.EventType
import com.zynnn.axwarestream.core.events.LiveEvent
import com.zynnn.axwarestream.core.tiktok.TikTokState
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.theme.Ax

private fun commentText(e: LiveEvent) = when (e.type) {
    EventType.CHAT -> e.message
    else -> listOf(e.type.label, e.message, e.amountText).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun nameColor(e: LiveEvent) = when (e.type) {
    EventType.CHAT -> Ax.Cyan
    EventType.GIFT, EventType.DONATION -> Ax.Gold
    else -> Ax.Ok
}

/**
 * Panel "Komentar live" di layar Axware untuk streamer. Hanya tampil di aplikasi,
 * tidak digambar ke video siaran.
 */
@Composable
fun LiveCommentsPanel(actions: AppActions, streaming: Boolean, showOnPreview: Boolean, onShowOnPreview: (Boolean) -> Unit) {
    val app = AxwareApp.instance
    val recent by app.hub.recentFlow.collectAsState()
    val tt by app.tiktok.status.collectAsState()
    var all by remember { mutableStateOf(false) }
    var chatWindow by remember { mutableStateOf(com.zynnn.axwarestream.service.StreamerChatService.running) }
    val items = recent.filter { if (all) it.type != EventType.LIKE && it.type != EventType.JOIN else it.type == EventType.CHAT }.takeLast(60)
    val state = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()?.id) { if (items.isNotEmpty()) state.animateScrollToItem(items.size - 1) }
    SectionCard(
        title = "Komentar live",
        subtitle = "Hanya terlihat oleh kamu di Axware, tidak ikut tersiar",
        action = {
            val (label, color) = when (tt.state) {
                TikTokState.CONNECTED -> "Tersambung" to Ax.Ok
                TikTokState.IDLE -> "Belum tersambung" to Ax.Muted
                TikTokState.ERROR -> "Gagal" to Ax.Live
                TikTokState.WAITING_LIVE -> "Menunggu live" to Ax.Warn
                else -> "Menyambung…" to Ax.Warn
            }
            Pill(label, color)
        },
    ) {
        Segmented(listOf(false, true), all, { if (it) "Semua interaksi" else "Komentar" }, { all = it })
        Box(
            Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(22.dp)).background(Ax.Surface2)
                .border(1.dp, Ax.Line, RoundedCornerShape(22.dp)),
        ) {
            if (items.isEmpty()) {
                Text(
                    if (tt.state == TikTokState.CONNECTED) "Belum ada komentar. Komentar penonton akan muncul di sini secara real-time."
                    else "Sambungkan username TikTok di atas agar komentar live muncul di sini.",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Muted,
                    modifier = Modifier.align(Alignment.Center).padding(20.dp),
                )
            } else {
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items) { e -> CommentRow(e) }
                }
            }
        }
        SwitchRow("Tampilkan di pratinjau kamera", "Komentar terbaru tampil di atas pratinjau (tidak ikut video)", showOnPreview, onShowOnPreview)
        SwitchRow("Jendela komentar mengambang",
            if (actions.canDrawOverlays()) "Baca komentar di atas game. Pada siaran layar penuh, jendela ini ikut terekam sistem."
            else "Butuh izin tampil di atas aplikasi lain", chatWindow) { on ->
            actions.setStreamerChat(on); chatWindow = on && actions.canDrawOverlays()
        }
    }
}

@Composable
private fun CommentRow(e: LiveEvent) {
    Row(verticalAlignment = Alignment.Top) {
        Initials(e.user.ifBlank { "?" }, nameColor(e), 26)
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = nameColor(e), fontWeight = FontWeight.SemiBold)) { append(e.user.ifBlank { "penonton" }) }
                append("  ")
                withStyle(SpanStyle(color = Ax.Text)) { append(commentText(e)) }
            },
            style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/** Komentar terbaru di atas pratinjau kamera (Compose, tidak masuk encoder). */
@Composable
fun PreviewCommentsOverlay(modifier: Modifier = Modifier) {
    val recent by AxwareApp.instance.hub.recentFlow.collectAsState()
    val chats = recent.filter { it.type == EventType.CHAT || it.type == EventType.GIFT }.takeLast(5)
    if (chats.isEmpty()) return
    Column(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chats.forEach { e ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = nameColor(e), fontWeight = FontWeight.SemiBold)) { append(e.user) }
                    append("  "); append(commentText(e))
                },
                style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
