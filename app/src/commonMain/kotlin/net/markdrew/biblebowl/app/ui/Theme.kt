package net.markdrew.biblebowl.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A calm, scholarly palette: deep indigo + warm gold accent on paper-white.
private val Indigo = Color(0xFF3B4A78)
private val IndigoDark = Color(0xFF283258)
private val Gold = Color(0xFFB88A2E)
private val Paper = Color(0xFFFAF8F4)
private val Ink = Color(0xFF1C1B1A)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2F3),
    onPrimaryContainer = IndigoDark,
    secondary = Gold,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEAE3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAEB9E8),
    onPrimary = IndigoDark,
    secondary = Color(0xFFE3C079),
    background = Color(0xFF14151A),
    surface = Color(0xFF1E2027),
)

@Composable
fun TbbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
