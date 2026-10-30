package com.zynnn.axwarestream.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zynnn.axwarestream.core.overlay.OverlayElement
import com.zynnn.axwarestream.core.overlay.WebLayerHost
import com.zynnn.axwarestream.ui.theme.Ax

/** Langkah mengambil link Alert Sociabuzz (sesuai panduan resmi Sociabuzz untuk aplikasi live mobile). */
val SOCIABUZZ_STEPS = listOf(
    "Login sociabuzz.com, buka menu TRIBE.",
    "Pilih \"Overlay Live Streaming\" lalu buka bagian Alert.",
    "Tekan \"Click to see the link\" dan salin link overlay-nya.",
    "Tempel di kolom Link di bawah, lalu tekan \"Test Alert\" di Sociabuzz untuk mencoba.",
)

/** Pengaturan elemen WEB: link halaman, tempel dari clipboard, validasi, skala & pratinjau langsung. */
@Composable
fun WebElementFields(el: OverlayElement, onChange: (OverlayElement) -> Unit) {
    val clip = LocalClipboardManager.current
    var preview by remember { mutableStateOf(false) }
    AxField(el.url, { v -> onChange(el.copy(url = v.trim())) }, "Link overlay (https://…)",
        placeholder = "https://sociabuzz.com/pro/tribe/alert…",
        trailing = {
            IconButton(onClick = { clip.getText()?.text?.trim()?.let { onChange(el.copy(url = it)) } }) {
                Icon(Icons.Rounded.ContentPaste, "Tempel", tint = Ax.Accent)
            }
        })
    val (msg, color) = when {
        el.url.isBlank() -> "Belum ada link. Ambil link Alert dari dashboard Sociabuzz." to Ax.Muted
        !WebLayerHost.isValidUrl(el.url) -> "Link tidak valid. Harus diawali https:// tanpa spasi." to Ax.Warn
        WebLayerHost.isSociabuzz(el.url) -> "Link Sociabuzz terdeteksi." to Ax.Ok
        else -> "Link web valid (bukan domain Sociabuzz, tetap bisa dipakai)." to Ax.Muted
    }
    Text(msg, style = MaterialTheme.typography.bodySmall, color = color)
    SliderRow("Skala halaman", el.pageScale, 0.5f..3f, { String.format(java.util.Locale.US, "%.2fx", it) }) { v -> onChange(el.copy(pageScale = v)) }
    OutlinedButton(onClick = { preview = true }, enabled = WebLayerHost.isValidUrl(el.url), shape = RoundedCornerShape(18.dp)) {
        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pratinjau langsung")
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SOCIABUZZ_STEPS.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, color = Ax.Muted) }
    }
    if (preview) WebPreviewDialog(el) { preview = false }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewDialog(el: OverlayElement, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ax.Surface,
        title = { Text("Pratinjau ${el.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Halaman dimuat langsung. Tekan \"Test Alert\" di dashboard Sociabuzz; alert akan muncul di kotak ini.",
                    style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
                Box(
                    Modifier.fillMaxWidth().aspectRatio((el.w / el.h).coerceIn(0.4f, 3f)).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF18223C)),
                ) {
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AColor.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient(); webViewClient = WebViewClient()
                            loadUrl(el.url)
                        }
                    }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
