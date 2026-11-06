package org.openmotion.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentKeyframe,
    tertiary = AccentPlayhead,
    background = BgWorkspace,
    surface = BgPanel,
    surfaceVariant = BgPanelElevated,
    onPrimary = BgWorkspace,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun OpenMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
