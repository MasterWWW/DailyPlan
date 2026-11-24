package com.dailyplan.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color(0xFFF8Faf7),
    primaryContainer = Color(0xFFD8E9E1),
    onPrimaryContainer = Color(0xFF15352C),
    secondary = Color(0xFF8A6548),
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFFCFBF8),
    surfaceVariant = Color(0xFFEAE6DE),
    error = Color(0xFFB0442D),
    onBackground = Color(0xFF23231F),
    onSurface = Color(0xFF23231F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BCAB8),
    onPrimary = Color(0xFF12372C),
    primaryContainer = Color(0xFF234D40),
    onPrimaryContainer = Color(0xFFD8EDE5),
    secondary = Color(0xFFD9B99F),
    background = Color(0xFF171815),
    surface = Color(0xFF20211E),
    surfaceVariant = Color(0xFF32332E),
    error = Color(0xFFFFB4A5),
    onBackground = Color(0xFFE7E4DC),
    onSurface = Color(0xFFE7E4DC),
)

@Composable
fun DailyPlanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
