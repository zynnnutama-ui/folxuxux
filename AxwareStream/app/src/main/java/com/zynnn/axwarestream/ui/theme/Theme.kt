package com.zynnn.axwarestream.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

object Ax {
    // Palet Midnight Slate & Electric Rose-Cyan Studio
    val Bg         = Color(0xFF060913)
    val Surface    = Color(0xFF10172A)
    val Surface2   = Color(0xFF18223C)
    val Surface3   = Color(0xFF223052)
    val Line       = Color(0xFF2E3F66)

    val Text       = Color(0xFFF8FAFC)
    val Muted      = Color(0xFF94A3B8)

    val Accent     = Color(0xFFFF2D55)
    val AccentSoft = Color(0x2EFF2D55)
    val Cyan       = Color(0xFF00F2FE)
    val CyanSoft   = Color(0x2600F2FE)

    val Ok         = Color(0xFF10B981)
    val Warn       = Color(0xFFF59E0B)
    val Live       = Color(0xFFFF2D55)
    val Gold       = Color(0xFFFBBF24)

    val Brand      = Brush.linearGradient(listOf(Color(0xFFFF2D55), Color(0xFFFF6B8B)))
}

private val Scheme = darkColorScheme(
    primary = Ax.Accent,
    onPrimary = Color.White,
    secondary = Ax.Cyan,
    onSecondary = Color.Black,
    tertiary = Ax.Gold,
    background = Ax.Bg,
    onBackground = Ax.Text,
    surface = Ax.Surface,
    onSurface = Ax.Text,
    surfaceVariant = Ax.Surface2,
    onSurfaceVariant = Ax.Muted,
    outline = Ax.Line,
    error = Ax.Live,
)

private val AxShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small      = RoundedCornerShape(16.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AxTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,      fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp),
    titleLarge    = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,      fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium   = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,  fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall    = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,  fontSize = 13.sp, lineHeight = 18.sp),
    bodyMedium    = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,    fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall     = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,    fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge    = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,  fontSize = 13.sp, lineHeight = 17.sp),
    labelMedium   = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,    fontSize = 11.sp, lineHeight = 14.sp),
    labelSmall    = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,    fontSize = 10.sp, lineHeight = 13.sp),
)

@Composable
fun AxwareTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ax.Bg.toArgb()
            window.navigationBarColor = Ax.Bg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(colorScheme = Scheme, typography = AxTypography, shapes = AxShapes, content = content)
}
