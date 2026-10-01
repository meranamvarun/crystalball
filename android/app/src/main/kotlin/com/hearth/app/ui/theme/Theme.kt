package com.hearth.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark "nocturne" palette from the design prototype: warm ember accent on near-black. */
object HearthColors {
    val Bg = Color(0xFF0F1115)
    val Surface = Color(0xFF181B22)
    val SurfaceHigh = Color(0xFF20242D)
    val Accent = Color(0xFFE8A15C)
    val AccentDeep = Color(0xFFB8733A)
    val Accent2 = Color(0xFF7FB3D5)
    val Text = Color(0xFFECEDEE)
    val Muted = Color(0xFF8A8F98)
    val Neutral = Color(0xFF3A3F4A)
    val NeutralLight = Color(0xFF5A606C)
}

@Composable
fun HearthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HearthColors.Accent,
            onPrimary = HearthColors.Bg,
            secondary = HearthColors.Accent2,
            background = HearthColors.Bg,
            onBackground = HearthColors.Text,
            surface = HearthColors.Surface,
            onSurface = HearthColors.Text,
            surfaceVariant = HearthColors.SurfaceHigh,
            onSurfaceVariant = HearthColors.Muted,
        ),
        content = content,
    )
}
