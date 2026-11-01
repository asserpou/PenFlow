package com.penflow.tablet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentBlue,
    background = DarkBackground,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = DarkBackground,
    onSurface = TextPrimary
)

@Composable
fun PenFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
