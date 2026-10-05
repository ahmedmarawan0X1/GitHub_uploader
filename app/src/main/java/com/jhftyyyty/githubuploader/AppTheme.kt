package com.jhftyyyty.githubuploader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF001A35),
    secondary = Color(0xFF57606A),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEEF2),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFF8C959F),
    outlineVariant = Color(0xFFD0D7DE),
    error = Color(0xFFCF222E),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFF82071E)
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF001A35),
    primaryContainer = Color(0xFF0D3158),
    onPrimaryContainer = Color(0xFFD1E6FF),
    secondary = Color(0xFFB1BAC4),
    onSecondary = Color(0xFF161B22),
    background = Color.Black,
    onBackground = Color(0xFFF0F3F6),
    surface = Color(0xFF080A0D),
    onSurface = Color(0xFFF0F3F6),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFFB1BAC4),
    outline = Color(0xFF6E7681),
    outlineVariant = Color(0xFF30363D),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF490202),
    errorContainer = Color(0xFF4A1715),
    onErrorContainer = Color(0xFFFFDCD7)
)

@Composable
internal fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) AmoledColors else LightColors,
        content = content
    )
}
