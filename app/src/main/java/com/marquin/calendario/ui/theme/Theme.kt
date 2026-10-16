package com.marquin.calendario.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF3E2C00),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = Color(0xFFFF8F00),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF4A2800),
    onSecondaryContainer = Color(0xFFFFCC80),
    tertiary = Color(0xFFFFD600),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFE0E0E0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFF8F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF3E0),
    onPrimaryContainer = Color(0xFFE65100),
    secondary = Color(0xFFFFB300),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFFFFECB3),
    onSecondaryContainer = Color(0xFFBF360C),
    tertiary = Color(0xFFFFD600)
)

@Composable
fun CalendarioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}