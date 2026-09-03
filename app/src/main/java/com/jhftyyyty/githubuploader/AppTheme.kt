package com.jhftyyyty.githubuploader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                background = Color.Black,
                onBackground = Color(0xFFE6E1E5),
                surface = Color.Black,
                onSurface = Color(0xFFE6E1E5),
                surfaceVariant = Color.Black,
                onSurfaceVariant = Color(0xFFCAC4D0),
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerHigh = Color.Black,
                surfaceContainerHighest = Color.Black,
                outline = Color(0xFF938F99),
                outlineVariant = Color(0xFF49454F)
            )
        } else {
            lightColorScheme(
                background = Color.White,
                surface = Color.White,
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color.White,
                surfaceContainer = Color.White,
                surfaceContainerHigh = Color.White,
                surfaceContainerHighest = Color.White
            )
        },
        content = content
    )
}
