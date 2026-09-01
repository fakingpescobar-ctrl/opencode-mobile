package org.opencode.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Терминальная тёмная палитра в духе opencode CLI
private val DarkColors = darkColorScheme(
    primary = Color(0xFF22C55E),
    onPrimary = Color(0xFF04100A),
    secondary = Color(0xFF64748B),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF11151A),
    onSurface = Color(0xFFE2E8F0),
    error = Color(0xFFEF4444),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF16A34A),
    onPrimary = Color(0xFFFAFAFA),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun OpencodeMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
