package com.selimdurmus.dictionary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TranslateColors = darkColorScheme(
    background = Background,
    surface = Surface,
    surfaceVariant = Surface,
    primary = Gold,
    secondary = Gold,
    tertiary = Gold,
    onBackground = OnHigh,
    onSurface = OnHigh,
    onSurfaceVariant = OnMedium,
    onPrimary = Background,
    outline = DividerSubtle,
    outlineVariant = DividerSubtle,
)

@Composable
fun TranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TranslateColors,
        typography = TranslateTypography,
        content = content,
    )
}
