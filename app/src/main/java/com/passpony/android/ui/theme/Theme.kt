package com.passpony.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Every role pinned, light and dark. Dynamic color stays off: the
// palette is the brand, and wallpaper colors would replace it.
private val LightColors = lightColorScheme(
    primary = PonyOrangeDeep,
    onPrimary = Color.White,
    primaryContainer = PonyOrangeTintLight,
    onPrimaryContainer = Color(0xFF351000),
    inversePrimary = Color(0xFFFFB68C),
    secondary = Color(0xFF6F625A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEE3DC),
    onSecondaryContainer = Color(0xFF261A13),
    tertiary = Color(0xFF685E52),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E2D2),
    onTertiaryContainer = Color(0xFF231A10),
    background = LightGround,
    onBackground = LightInk,
    surface = LightGround,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFEEE7E2),
    onSurfaceVariant = LightMuted,
    surfaceTint = PonyOrangeDeep,
    inverseSurface = Color(0xFF34302D),
    inverseOnSurface = Color(0xFFF7EFEA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = LightOutline,
    outlineVariant = LightLine,
    scrim = Color.Black,
    surfaceBright = LightGround,
    surfaceDim = Color(0xFFDDD8D4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F3F0),
    surfaceContainer = Color(0xFFF2EDEA),
    surfaceContainerHigh = Color(0xFFECE7E3),
    surfaceContainerHighest = Color(0xFFE6E1DD),
)

private val DarkColors = darkColorScheme(
    primary = PonyOrangeLight,
    onPrimary = Color(0xFF3A1700),
    primaryContainer = PonyOrangeTintDark,
    onPrimaryContainer = PonyOrangeTintLight,
    inversePrimary = PonyOrangeDeep,
    secondary = Color(0xFFD8C2B7),
    onSecondary = Color(0xFF3B2D25),
    secondaryContainer = Color(0xFF52443B),
    onSecondaryContainer = Color(0xFFF5DED2),
    tertiary = Color(0xFFD6C6AE),
    onTertiary = Color(0xFF3A2F1F),
    tertiaryContainer = Color(0xFF514533),
    onTertiaryContainer = Color(0xFFF3E2C9),
    background = DarkGround,
    onBackground = DarkInk,
    surface = DarkGround,
    onSurface = DarkInk,
    surfaceVariant = Color(0xFF3A332E),
    onSurfaceVariant = DarkMuted,
    surfaceTint = PonyOrangeLight,
    inverseSurface = DarkInk,
    inverseOnSurface = Color(0xFF34302D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = DarkOutline,
    outlineVariant = DarkLine,
    scrim = Color.Black,
    surfaceBright = Color(0xFF3D3734),
    surfaceDim = DarkGround,
    surfaceContainerLowest = Color(0xFF110E0C),
    surfaceContainerLow = Color(0xFF1E1A17),
    surfaceContainer = Color(0xFF221E1B),
    surfaceContainerHigh = Color(0xFF2D2825),
    surfaceContainerHighest = Color(0xFF383330),
)

@Composable
fun PassPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PassPonyTypography,
        content = content
    )
}

/**
 * Amber for something missing or needing attention, on an icon and its
 * status word only. Follows the scheme in use (dark when its background
 * is dark), not the system setting, so it matches whatever theme is
 * applied.
 */
val ColorScheme.statusWarn: Color
    get() = if (background.luminance() < 0.5f) StatusAmberDark else StatusAmberLight
