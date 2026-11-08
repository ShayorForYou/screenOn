package com.screenon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = NightBg,
    tertiary = Champagne,
    background = NightBg,
    surface = NightBg,
    onBackground = NightText,
    onSurface = NightText,
    onSurfaceVariant = NightMuted,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightRaised,
    outline = NightLine,
    outlineVariant = NightLine,
)

private val LightColors = lightColorScheme(
    primary = DeepGold,
    onPrimary = IvoryBg,
    tertiary = Bronze,
    background = IvoryBg,
    surface = IvoryBg,
    onBackground = InkText,
    onSurface = InkText,
    onSurfaceVariant = InkMuted,
    surfaceContainer = IvoryRaised,
    surfaceContainerHigh = IvoryRaised,
    outline = IvoryLine,
    outlineVariant = IvoryLine,
)

/** Fixed brand palette (no wallpaper colours) so the app always matches its icon. */
@Composable
fun ScreenonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
