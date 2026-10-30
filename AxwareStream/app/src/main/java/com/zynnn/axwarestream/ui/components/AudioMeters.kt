package com.zynnn.axwarestream.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.core.audio.AudioLevels
import com.zynnn.axwarestream.core.audio.AudioMeter
import com.zynnn.axwarestream.core.audio.AudioTester
import com.zynnn.axwarestream.ui.AppActions
import com.zynnn.axwarestream.ui.theme.Ax
import kotlin.math.log10

/** Level RMS 0..1 -> posisi meter 0..1 memakai skala dB (-60 dB .. 0 dB). */
fun meterPos(rms: Float): Float = if (rms <= 0.001f) 0f else ((20 * log10(rms) + 60f) / 60f).coerceIn(0f, 1f)

@Composable
fun LevelMeter(icon: ImageVector, label: String, level: Float, max: Float, enabled: Boolean, color: Color, error: String? = null) {
    val pos by animateFloatAsState(if (enabled) meterPos(level) else 0f, tween(90), label = "lvl")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) color else Ax.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            val status = when {
                error != null -> "Gagal"
                !enabled -> "Tidak dipakai"
                max >= 0.01f -> "Ada suara"
                else -> "Hening"
            }
            Text(status, style = MaterialTheme.typography.labelSmall,
                color = when { error != null -> Ax.Live; !enabled -> Ax.Muted; max >= 0.01f -> Ax.Ok; else -> Ax.Warn })
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)).background(Ax.Surface3)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(pos).clip(RoundedCornerShape(8.dp))
                .background(if (pos > 0.92f) Ax.Live else color))
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = Ax.Live)
    }
}

@Composable
fun AudioLevelRows(l: AudioLevels) {
    LevelMeter(Icons.Rounded.Mic, "Mikrofon", l.mic, l.micMax, l.micEnabled, Ax.Cyan, l.micError)
    LevelMeter(Icons.Rounded.GraphicEq, "Audio internal (game)", l.internal, l.internalMax, l.internalEnabled, Ax.Accent, l.internalError)
}

/** Meter audio saat live mode layar (internal / campuran). */
@Composable
fun LiveAudioCard() {
    val l by AudioMeter.levels.collectAsState()
    if (!l.running) return
    SectionCard(title = "Level audio siaran", subtitle = "Pastikan bar audio internal bergerak saat game bersuara") {
        AudioLevelRows(l)
        if (l.internalEnabled && l.internalMax < 0.01f)
            Text("Audio internal masih hening. Pastikan game bersuara, Bluetooth mati, dan game mengizinkan suaranya direkam.",
                style = MaterialTheme.typography.bodySmall, color = Ax.Muted)
    }
}

/** Kartu Tes audio di Setelan: uji mic + internal tanpa siaran, nada tes, dan putar ulang. */
@Composable
fun AudioTestCard(actions: AppActions) {
    val st by AudioTester.state.collectAsState()
    val l by AudioMeter.levels.collectAsState()
    val running = st.phase == AudioTester.Phase.RUNNING
    SectionCard(title = "Tes audio", subtitle = "Uji mikrofon + audio internal seperti saat live (tanpa siaran)", action = {
        if (running) Pill("${st.seconds} dtk", Ax.Live)
    }) {
        if (running || st.phase == AudioTester.Phase.DONE) AudioLevelRows(l)
        Text(
            if (running) "Bicara ke mikrofon, lalu tekan \"Putar nada tes\" atau buka game/musik. Bar audio internal harus bergerak."
            else "Android akan meminta izin rekam layar; izin itu dibutuhkan sistem untuk merekam audio internal.",
            style = MaterialTheme.typography.bodySmall, color = Ax.Muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (running) {
                Button(onClick = { actions.stopAudioTest() }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Ax.Surface3)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop", maxLines = 1)
                }
                OutlinedButton(onClick = { AudioTester.playTone() }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1.4f)) {
                    Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Putar nada tes", maxLines = 1)
                }
            } else {
                Button(onClick = { actions.startAudioTest() }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (st.phase == AudioTester.Phase.DONE) "Tes lagi" else "Mulai tes", maxLines = 1)
                }
                if (st.phase == AudioTester.Phase.DONE && st.recordedMs > 0)
                    OutlinedButton(onClick = { AudioTester.playRecording() }, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1.4f)) {
                        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text("Dengar hasil (${st.recordedMs / 1000} dtk)", maxLines = 1)
                    }
            }
        }
        AudioTester.verdict(l, st)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (l.internalMax >= 0.01f) Ax.Ok else Ax.Warn) }
        st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ax.Live) }
    }
}
