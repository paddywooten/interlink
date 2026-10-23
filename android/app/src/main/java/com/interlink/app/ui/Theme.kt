package com.interlink.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark scheme echoing the desktop app (#0B0E14 background, #4F8CFF accent). */
private val IlColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    secondary = Color(0xFF7AA2F7),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFD6DEEB),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFD6DEEB),
    surfaceVariant = Color(0xFF1C2330),
    onSurfaceVariant = Color(0xFF9AA5B8),
    error = Color(0xFFF7768E),
)

@Composable
fun InterLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IlColors, content = content)
}
