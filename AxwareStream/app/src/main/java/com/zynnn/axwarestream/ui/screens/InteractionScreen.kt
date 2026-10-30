package com.zynnn.axwarestream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.zynnn.axwarestream.core.events.Platform
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.components.ChipRow
import com.zynnn.axwarestream.ui.components.EmptyState
import com.zynnn.axwarestream.ui.components.Initials
import com.zynnn.axwarestream.ui.components.Pill
import com.zynnn.axwarestream.ui.components.ScreenHeader
import com.zynnn.axwarestream.ui.components.SectionCard
import com.zynnn.axwarestream.ui.components.StatTile
import com.zynnn.axwarestream.ui.components.TikTokAccountCard
import com.zynnn.axwarestream.ui.components.compact
import com.zynnn.axwarestream.ui.components.hexColor
import com.zynnn.axwarestream.ui.theme.Ax

private enum class Filter(val label: String, val type: EventType?) {
    ALL("Semua", null), CHAT("Komentar", EventType.CHAT), GIFT("Gift", EventType.GIFT),
    LIKE("Like", EventType.LIKE), FOLLOW("Follow", EventType.FOLLOW), SHARE("Share", EventType.SHARE),
    REPOST("Repost", EventType.REPOST),
}

fun typeColor(t: EventType): Color = when (t) {
    EventType.GIFT -> Ax.Accent
    EventType.FOLLOW -> Ax.Cyan
    EventType.SHARE -> Ax.Ok
    EventType.REPOST -> Color(0xFFA98BFF)
    EventType.LIKE -> Color(0xFFFF6B8B)
    EventType.SUBSCRIBE -> Ax.Gold
    EventType.DONATION -> Color(0xFFF59E0B)
    else -> Ax.Muted
}

/** Tab Interaksi: koneksi TikTok LIVE, statistik, Top Gifter, dan feed komentar/gift/like. */
@Composable
fun InteractionScreen(actions: AppActions) {
    val app = AxwareApp.instance
    val stats by app.hub.stats.collectAsState()
    val recent by app.hub.recentFlow.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val list = recent.asReversed().asSequence().filter { filter.type == null || it.type == filter.type }.take(60).toList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Interaksi", "Komentar, gift & like dari TikTok LIVE") {
            Pill("${compact(stats.totalViewers)} penonton", Ax.Cyan)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TikTokAccountCard(actions, showStreamKey = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(compact(stats.diamondTotal), "Diamond", Icons.Rounded.Diamond, Ax.Cyan, Modifier.weight(1f))
                StatTile(compact(stats.likeCount), "Like", Icons.Rounded.Favorite, Ax.Accent, Modifier.weight(1f))
                StatTile(compact(stats.chatCount), "Komentar", Icons.Rounded.ChatBubble, Ax.Text, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(compact(stats.giftCount), "Gift", Icons.Rounded.CardGiftcard, Ax.Gold, Modifier.weight(1f))
                StatTile(compact(stats.followCount), "Follow", Icons.Rounded.PersonAdd, Ax.Ok, Modifier.weight(1f))
                StatTile(compact(stats.shareCount), "Share", Icons.Rounded.Share, Ax.Cyan, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(compact(stats.repostCount), "Repost", Icons.Rounded.Repeat, Color(0xFFA98BFF), Modifier.weight(1f))
                StatTile(compact(stats.joinCount), "Masuk", Icons.Rounded.Login, Ax.Muted, Modifier.weight(1f))
                StatTile(compact(stats.subCount), "Subscribe", Icons.Rounded.Star, Ax.Gold, Modifier.weight(1f))
            }

            SectionCard(title = "Top Gifter", subtitle = "Berdasarkan total diamond sesi ini") {
                if (stats.topGifters.isEmpty()) Text("Belum ada gift.", style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
                stats.topGifters.take(5).forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val medal = when (i) { 0 -> Ax.Gold; 1 -> Color(0xFFC9CED6); 2 -> Color(0xFFD08B5B); else -> Ax.Muted }
                        Box(Modifier.size(24.dp).clip(CircleShape).background(medal.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = medal, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(r.user, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${compact(r.diamonds)} diamond", style = MaterialTheme.typography.labelMedium, color = Ax.Cyan)
                    }
                }
            }

            SectionCard(title = "Aktivitas live", subtitle = "Terbaru di atas", action = {
                TextButton(onClick = { app.hub.reset() }) { Text("Reset", color = Ax.Muted) }
            }) {
                ChipRow(Filter.entries, filter, { it.label }, { filter = it })
                if (list.isEmpty()) EmptyState(Icons.Rounded.Forum, "Belum ada aktivitas",
                    "Hubungkan akun TikTok yang sedang live. Komentar, gift, like, follow, share, repost dan penonton masuk akan tampil di sini.")
                list.forEach { EventRow(it) }
            }

            SimulateCard()
            Text("Pembacaan event memakai protokol web TikTok LIVE (tidak resmi) melalui server tanda tangan Euler Stream. " +
                "Bila TikTok mengubah sistemnya, fitur ini bisa berhenti sementara.",
                style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EventRow(e: LiveEvent) {
    val c = typeColor(e.type)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (e.type == EventType.GIFT) Ax.AccentSoft else Ax.Surface2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Initials(e.user, if (e.userColor.isNotBlank()) hexColor(e.userColor) else c, 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.user, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.type != EventType.CHAT) { Spacer(Modifier.width(6.dp)); Pill(e.type.label, c) }
            }
            val body = buildAnnotatedString {
                when (e.type) {
                    EventType.CHAT -> append(e.message)
                    EventType.GIFT -> {
                        append(e.message.ifBlank { "Mengirim gift" })
                        if (e.amountText.isNotBlank()) { append(" "); withStyle(SpanStyle(color = Ax.Text, fontWeight = FontWeight.SemiBold)) { append(e.amountText) } }
                        if (e.diamonds > 0) withStyle(SpanStyle(color = Ax.Cyan)) { append("  ·  ${e.diamonds} diamond") }
                    }
                    else -> { append(e.message); if (e.amountText.isNotBlank()) append(" " + e.amountText) }
                }
            }
            if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = if (e.type == EventType.CHAT) Ax.Text else Ax.Muted)
        }
    }
}

@Composable
private fun SimulateCard() {
    val hub = AxwareApp.instance.hub
    SectionCard(title = "Uji overlay", subtitle = "Kirim event contoh untuk mencoba alert & widget") {
        val samples = listOf(
            "Komentar" to { LiveEvent(Platform.SYSTEM, EventType.CHAT, "Penonton", "Halo kak, semangat live-nya") },
            "Gift Rose" to { LiveEvent(Platform.SYSTEM, EventType.GIFT, "Pemberi Gift", "Rose", 1, "x1", diamonds = 1) },
            "Gift x10" to { LiveEvent(Platform.SYSTEM, EventType.GIFT, "Sultan", "Finger Heart", 10, "x10", diamonds = 50) },
            "Follow" to { LiveEvent(Platform.SYSTEM, EventType.FOLLOW, "Penonton Baru", "mengikuti host") },
            "Share" to { LiveEvent(Platform.SYSTEM, EventType.SHARE, "Penonton", "membagikan live") },
            "Like" to { LiveEvent(Platform.SYSTEM, EventType.LIKE, "Penonton", "menyukai live", 15, "x15") },
            "Repost" to { LiveEvent(Platform.SYSTEM, EventType.REPOST, "Penonton", "memposting ulang live") },
            "Masuk" to { LiveEvent(Platform.SYSTEM, EventType.JOIN, "Penonton", "bergabung") },
            "Komentar 2" to { LiveEvent(Platform.SYSTEM, EventType.CHAT, "Penonton Lain", "Mantap, lanjut kak") },
        )
        samples.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, make) ->
                    OutlinedButton(onClick = { hub.emit(make()) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        Text("Event contoh ditandai sumber \"Axware\" dan tidak dikirim ke TikTok.", style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
    }
}
