package com.vaulthome.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Black = Color(0xFF000000)
private val Ink = Color(0xFFE6E6E6)
private val Muted = Color(0xFF8A8A8A)

private val Scheme = darkColorScheme(
    primary = Color(0xFFC8C8C8),
    onPrimary = Black,
    background = Black,
    onBackground = Ink,
    surface = Black,
    onSurface = Ink,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Muted,
    outline = Color(0xFF333333),
)

@Composable
fun VaultHomeTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Black.toArgb()
            window.navigationBarColor = Black.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = Scheme, content = content)
}
