package net.markdrew.biblebowl.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The texasbiblebowl.org brand: navy + gold on paper-white (see docs/gui-redesign.md §6).
// One palette across the static site and the app.
private val Navy = Color(0xFF1A3A5C)
private val NavyDark = Color(0xFF102A46)
private val Gold = Color(0xFFC9952A)
private val Paper = Color(0xFFFAF8F4)
private val Ink = Color(0xFF1C1B1A)

private val LightScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E3F0),
    onPrimaryContainer = NavyDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E4C3),
    onSecondaryContainer = Color(0xFF5C3F00),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEAE3),
)

// Night studying is real: keep dark mode, re-tinted to the navy/gold brand.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FBAD8),
    onPrimary = NavyDark,
    primaryContainer = Color(0xFF2A4A6E),
    onPrimaryContainer = Color(0xFFD8E3F0),
    secondary = Color(0xFFE3C079),
    onSecondary = Color(0xFF3F2E00),
    background = Color(0xFF14161A),
    surface = Color(0xFF1D2026),
)

@Composable
fun TbbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
