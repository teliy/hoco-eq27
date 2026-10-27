package com.eq27.anc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E4F),
    primaryContainer = Color(0xFF1D3A5F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF9AD0B3),
    onSecondary = Color(0xFF0B3A24),
    secondaryContainer = Color(0xFF1E4634),
    onSecondaryContainer = Color(0xFFD7F0E2),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF181E26),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF232B36),
    onSurfaceVariant = Color(0xFFB7C0CC),
    outline = Color(0xFF3D4854),
)

/** Тёмная тема «в стиле наушников»: синий акцент + графитовый фон. */
@Composable
fun Eq27Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
