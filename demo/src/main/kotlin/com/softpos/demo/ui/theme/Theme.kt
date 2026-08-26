package com.softpos.demo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00696D)
private val TealLight = Color(0xFF6FF6FC)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A6365),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = Color(0xFFB1CBCD),
)

@Composable
fun SoftPosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
