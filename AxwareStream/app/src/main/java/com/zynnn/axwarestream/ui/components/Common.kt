package com.zynnn.axwarestream.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zynnn.axwarestream.ui.theme.Ax

@Composable
fun BrandMark(size: Dp = 36.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.36f))
            .background(Color(0xFF10172A))
            .border(1.dp, Ax.Line, RoundedCornerShape(size * 0.36f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(size * 0.18f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.13f
            drawLine(
                color = Color(0xFF00F2FE),
                start = Offset(w * 0.16f, h * 0.86f),
                end = Offset(w * 0.50f, h * 0.14f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFFF2D55),
                start = Offset(w * 0.50f, h * 0.14f),
                end = Offset(w * 0.84f, h * 0.86f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                brush = Brush.horizontalGradient(listOf(Color(0xFF00F2FE), Color(0xFFFF2D55))),
                start = Offset(w * 0.26f, h * 0.62f),
                end = Offset(w * 0.74f, h * 0.62f),
                strokeWidth = stroke * 0.9f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = Color.White,
                radius = stroke * 0.42f,
                center = Offset(w * 0.50f, h * 0.14f)
            )
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    brand: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (brand) {
            BrandMark(38.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Ax.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ax.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

@Composable
fun AxCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    accentBorder: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ax.Surface)
            .border(1.dp, accentBorder ?: Ax.Line.copy(alpha = 0.55f), shape)
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Ax.Text)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = Ax.Muted)
        }
    }
}

@Composable
fun Pill(text: String, color: Color = Ax.Accent, filled: Boolean = false) {
    val bg = if (filled) color else color.copy(alpha = 0.18f)
    val fg = if (filled) Color.White else color
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Ax.Surface2)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        options.forEach { item ->
            val sel = item == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (sel) Ax.Accent else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sel) Color.White else Ax.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun VuBar(level: Float, modifier: Modifier = Modifier, color: Color = Ax.Ok) {
    val clamped = level.coerceIn(0f, 1f)
    Canvas(modifier.height(8.dp).clip(RoundedCornerShape(8.dp)).background(Ax.Surface2)) {
        val w = size.width * clamped
        if (w > 0f) {
            drawRoundRect(
                color = if (clamped > 0.85f) Ax.Live else if (clamped > 0.65f) Ax.Warn else color,
                size = Size(w, size.height),
                cornerRadius = CornerRadius(size.height / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, accent: Color = Ax.Text, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Ax.Surface2)
            .border(1.dp, Ax.Line.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Ax.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    active: Boolean = false,
    danger: Boolean = false,
) {
    val bg = when {
        danger -> Ax.Live.copy(alpha = 0.2f)
        active -> Ax.AccentSoft
        else   -> Ax.Surface2
    }
    val tint = when {
        danger -> Ax.Live
        active -> Ax.Accent
        else   -> Ax.Text
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (active) Ax.Accent.copy(alpha = 0.5f) else Ax.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun RingGauge(progress: Float, label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(66.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawArc(
                color = Ax.Surface3,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = Ax.Text)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Ax.Muted)
        }
    }
}
