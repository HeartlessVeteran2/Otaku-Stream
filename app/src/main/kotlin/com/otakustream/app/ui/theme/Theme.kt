package com.otakustream.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// A "Spider-Man 2" (2004) inspired dark theme — a video app lives in the dark, so this is
// the only theme. Midnight-navy backgrounds, ink-muted crimson primary, warm gold tertiary.
private val OtakuColors = darkColorScheme(
    primary = CrimsonInk,
    onPrimary = PosterCream,
    primaryContainer = ShadowRed,
    onPrimaryContainer = WarmPeach,
    secondary = SteelBlue,
    onSecondary = DeepNavyText,
    tertiary = WebGold,
    onTertiary = BronzeDark,
    tertiaryContainer = BronzeContainer,
    onTertiaryContainer = PaleGold,
    background = MidnightNavy,
    onBackground = ParchmentWhite,
    surface = LiftedNavy,
    onSurface = ParchmentWhite,
    surfaceVariant = SlateBlueVariant,
    onSurfaceVariant = CoolGreyBlue,
    outline = InkOutline,
    outlineVariant = InkOutlineVariant,
    error = EmberError,
    onError = EmberErrorDark,
)

@Composable
fun OtakuStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OtakuColors, typography = OtakuTypography, shapes = OtakuShapes, content = content)
}
