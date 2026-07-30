package com.nexgo.iptv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NexGoColorScheme = darkColorScheme(
    primary = NexGoPrimary,
    onPrimary = NexGoBackground,
    secondary = NexGoAccent,
    background = NexGoBackground,
    surface = NexGoSurface,
    onBackground = NexGoText,
    onSurface = NexGoText
)

@Composable
fun NexGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NexGoColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
