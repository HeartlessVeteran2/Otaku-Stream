package com.otakustream.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Two things this file exists to stop happening again.
//
// The first is the bug it was written for. The theme it replaced assigned nineteen roles and left
// ten unassigned, and an unassigned role is not an error — darkColorScheme() fills it from
// Material's baseline, which is purple. So the bottom-nav selected indicator, every selected
// FilterChip, card elevation and the snackbar all drew baseline purple over a navy-and-crimson
// app, for as long as nobody looked closely.
//
// The check names every role and the constant it must hold. The first attempt was cheaper — flag
// any role still equal to Material's baseline — and it was wrong: in the light scheme onPrimary,
// onSecondary, onTertiary, onError and surfaceContainerLowest are all pure white, which is also
// what the baseline uses, so a value comparison cannot tell an assignment from an omission. It
// duplicates the table in Theme.kt on purpose. That is the guard: dropping a line there without
// dropping it here is exactly the mistake being guarded against, and it fails loudly instead of
// turning something purple.
//
// The second is contrast. Reviewing hex codes by eye does not tell you whether text will be
// legible, and the light scheme in particular was designed here without a device to check it on.
// Every pair that ends up as text-on-fill is asserted against WCAG AA.
class ThemeTest {

    @Test
    fun `dark scheme assigns every role`() {
        assertRoles(OtakuDarkColors, darkRoles())
    }

    @Test
    fun `light scheme assigns every role`() {
        assertRoles(OtakuLightColors, lightRoles())
    }

    @Test
    fun `dark scheme text pairs meet WCAG AA`() {
        assertAllPairsReadable(OtakuDarkColors)
    }

    @Test
    fun `light scheme text pairs meet WCAG AA`() {
        assertAllPairsReadable(OtakuLightColors)
    }

    // surfaceTint is what Material multiplies into an elevated surface. Pointing it at anything but
    // primary makes raised cards drift towards a colour that appears nowhere else in the scheme,
    // which is a subtle version of the same bug.
    @Test
    fun `surface tint follows primary`() {
        assertEquals(OtakuDarkColors.primary, OtakuDarkColors.surfaceTint)
        assertEquals(OtakuLightColors.primary, OtakuLightColors.surfaceTint)
    }

    // The two schemes have to be genuinely different documents, not one with a couple of edits —
    // a copy-paste slip that left a dark surface in the light scheme would otherwise sail through
    // the contrast checks above, because its onSurface would have been copied with it.
    @Test
    fun `light scheme is lighter than dark scheme`() {
        assertTrue(luminance(OtakuLightColors.surface) > 0.7)
        assertTrue(luminance(OtakuDarkColors.surface) < 0.05)
    }

    private fun assertRoles(scheme: ColorScheme, expected: Map<String, Color>) {
        val actual = allRoles(scheme).toMap()
        assertEquals("role names", expected.keys, actual.keys)
        expected.forEach { (name, want) ->
            assertEquals("$name is not the colour Color.kt defines for it", want, actual[name])
        }
    }

    private fun assertAllPairsReadable(scheme: ColorScheme) {
        textPairs(scheme).forEach { (name, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue(
                "$name has contrast %.2f:1, below the 4.5:1 needed for body text".format(ratio),
                ratio >= MIN_TEXT_CONTRAST,
            )
        }
    }
}

private const val MIN_TEXT_CONTRAST = 4.5

// The intended tables, stated here independently of Theme.kt. A role added to one and not the other
// fails on assertRoles' key comparison rather than silently going unchecked — that comparison is
// over sets, so the order these are written in is presentation only and nothing depends on it.
private fun darkRoles(): Map<String, Color> = linkedMapOf(
    "primary" to DarkPrimary,
    "onPrimary" to DarkOnPrimary,
    "primaryContainer" to DarkPrimaryContainer,
    "onPrimaryContainer" to DarkOnPrimaryContainer,
    "inversePrimary" to DarkInversePrimary,
    "secondary" to DarkSecondary,
    "onSecondary" to DarkOnSecondary,
    "secondaryContainer" to DarkSecondaryContainer,
    "onSecondaryContainer" to DarkOnSecondaryContainer,
    "tertiary" to DarkTertiary,
    "onTertiary" to DarkOnTertiary,
    "tertiaryContainer" to DarkTertiaryContainer,
    "onTertiaryContainer" to DarkOnTertiaryContainer,
    "background" to DarkBackground,
    "onBackground" to DarkOnBackground,
    "surface" to DarkSurface,
    "onSurface" to DarkOnSurface,
    "surfaceVariant" to DarkSurfaceVariant,
    "onSurfaceVariant" to DarkOnSurfaceVariant,
    "surfaceTint" to DarkPrimary,
    "inverseSurface" to DarkInverseSurface,
    "inverseOnSurface" to DarkInverseOnSurface,
    "error" to DarkError,
    "onError" to DarkOnError,
    "errorContainer" to DarkErrorContainer,
    "onErrorContainer" to DarkOnErrorContainer,
    "outline" to DarkOutline,
    "outlineVariant" to DarkOutlineVariant,
    "scrim" to ScrimBlack,
    "surfaceBright" to DarkSurfaceBright,
    "surfaceDim" to DarkSurfaceDim,
    "surfaceContainer" to DarkSurfaceContainer,
    "surfaceContainerHigh" to DarkSurfaceContainerHigh,
    "surfaceContainerHighest" to DarkSurfaceContainerHighest,
    "surfaceContainerLow" to DarkSurfaceContainerLow,
    "surfaceContainerLowest" to DarkSurfaceContainerLowest,
)

private fun lightRoles(): Map<String, Color> = linkedMapOf(
    "primary" to LightPrimary,
    "onPrimary" to LightOnPrimary,
    "primaryContainer" to LightPrimaryContainer,
    "onPrimaryContainer" to LightOnPrimaryContainer,
    "inversePrimary" to LightInversePrimary,
    "secondary" to LightSecondary,
    "onSecondary" to LightOnSecondary,
    "secondaryContainer" to LightSecondaryContainer,
    "onSecondaryContainer" to LightOnSecondaryContainer,
    "tertiary" to LightTertiary,
    "onTertiary" to LightOnTertiary,
    "tertiaryContainer" to LightTertiaryContainer,
    "onTertiaryContainer" to LightOnTertiaryContainer,
    "background" to LightBackground,
    "onBackground" to LightOnBackground,
    "surface" to LightSurface,
    "onSurface" to LightOnSurface,
    "surfaceVariant" to LightSurfaceVariant,
    "onSurfaceVariant" to LightOnSurfaceVariant,
    "surfaceTint" to LightPrimary,
    "inverseSurface" to LightInverseSurface,
    "inverseOnSurface" to LightInverseOnSurface,
    "error" to LightError,
    "onError" to LightOnError,
    "errorContainer" to LightErrorContainer,
    "onErrorContainer" to LightOnErrorContainer,
    "outline" to LightOutline,
    "outlineVariant" to LightOutlineVariant,
    "scrim" to ScrimBlack,
    "surfaceBright" to LightSurfaceBright,
    "surfaceDim" to LightSurfaceDim,
    "surfaceContainer" to LightSurfaceContainer,
    "surfaceContainerHigh" to LightSurfaceContainerHigh,
    "surfaceContainerHighest" to LightSurfaceContainerHighest,
    "surfaceContainerLow" to LightSurfaceContainerLow,
    "surfaceContainerLowest" to LightSurfaceContainerLowest,
)

private fun allRoles(s: ColorScheme): List<Pair<String, Color>> = listOf(
    "primary" to s.primary,
    "onPrimary" to s.onPrimary,
    "primaryContainer" to s.primaryContainer,
    "onPrimaryContainer" to s.onPrimaryContainer,
    "inversePrimary" to s.inversePrimary,
    "secondary" to s.secondary,
    "onSecondary" to s.onSecondary,
    "secondaryContainer" to s.secondaryContainer,
    "onSecondaryContainer" to s.onSecondaryContainer,
    "tertiary" to s.tertiary,
    "onTertiary" to s.onTertiary,
    "tertiaryContainer" to s.tertiaryContainer,
    "onTertiaryContainer" to s.onTertiaryContainer,
    "background" to s.background,
    "onBackground" to s.onBackground,
    "surface" to s.surface,
    "onSurface" to s.onSurface,
    "surfaceVariant" to s.surfaceVariant,
    "onSurfaceVariant" to s.onSurfaceVariant,
    "surfaceTint" to s.surfaceTint,
    "inverseSurface" to s.inverseSurface,
    "inverseOnSurface" to s.inverseOnSurface,
    "error" to s.error,
    "onError" to s.onError,
    "errorContainer" to s.errorContainer,
    "onErrorContainer" to s.onErrorContainer,
    "outline" to s.outline,
    "outlineVariant" to s.outlineVariant,
    "scrim" to s.scrim,
    "surfaceBright" to s.surfaceBright,
    "surfaceDim" to s.surfaceDim,
    "surfaceContainer" to s.surfaceContainer,
    "surfaceContainerHigh" to s.surfaceContainerHigh,
    "surfaceContainerHighest" to s.surfaceContainerHighest,
    "surfaceContainerLow" to s.surfaceContainerLow,
    "surfaceContainerLowest" to s.surfaceContainerLowest,
)

// scrim and the outlines are absent: they are never drawn under text, and holding a 1dp divider to
// a body-text contrast ratio would only push it towards being loud enough to notice.
private fun textPairs(s: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
    "onPrimary on primary" to (s.primary to s.onPrimary),
    "onPrimaryContainer on primaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
    "onSecondary on secondary" to (s.secondary to s.onSecondary),
    "onSecondaryContainer on secondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
    "onTertiary on tertiary" to (s.tertiary to s.onTertiary),
    "onTertiaryContainer on tertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
    "onError on error" to (s.error to s.onError),
    "onErrorContainer on errorContainer" to (s.errorContainer to s.onErrorContainer),
    "onBackground on background" to (s.background to s.onBackground),
    "onSurface on surface" to (s.surface to s.onSurface),
    "onSurfaceVariant on surfaceVariant" to (s.surfaceVariant to s.onSurfaceVariant),
    "inverseOnSurface on inverseSurface" to (s.inverseSurface to s.inverseOnSurface),
    "inversePrimary on inverseSurface" to (s.inverseSurface to s.inversePrimary),
    // Text buttons and links draw primary straight onto the page, so primary has to be readable
    // as ink and not only as a fill.
    "primary as text on background" to (s.background to s.primary),
    "secondary as text on background" to (s.background to s.secondary),
    "tertiary as text on background" to (s.background to s.tertiary),
    "error as text on background" to (s.background to s.error),
    // The surface-container family carries onSurface text on cards, sheets and menus.
    "onSurface on surfaceContainerLowest" to (s.surfaceContainerLowest to s.onSurface),
    "onSurface on surfaceContainerLow" to (s.surfaceContainerLow to s.onSurface),
    "onSurface on surfaceContainer" to (s.surfaceContainer to s.onSurface),
    "onSurface on surfaceContainerHigh" to (s.surfaceContainerHigh to s.onSurface),
    "onSurface on surfaceContainerHighest" to (s.surfaceContainerHighest to s.onSurface),
    "onSurface on surfaceBright" to (s.surfaceBright to s.onSurface),
    "onSurface on surfaceDim" to (s.surfaceDim to s.onSurface),
)

// WCAG 2.1 relative luminance and contrast ratio, on opaque sRGB. Both schemes are fully opaque,
// so alpha is deliberately not compensated for — a translucent colour here would be a bug worth
// noticing rather than papering over.
private fun luminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

private fun contrastRatio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
