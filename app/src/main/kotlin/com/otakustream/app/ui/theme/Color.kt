package com.otakustream.app.ui.theme

import androidx.compose.ui.graphics.Color

// The palette, as two complete tables — one per scheme.
//
// Named by Material role rather than poetically, on purpose. The palette this replaced had
// evocative names (CrimsonInk, MidnightNavy) for nineteen colours, and the cost of that was the
// bug this file exists to fix: with names that don't say which role they fill, there was no way to
// see at a glance that ten roles were never assigned at all. Unassigned roles don't fail loudly —
// they silently fall back to Material's stock baseline, which is purple. So the bottom-navigation
// selected indicator, every selected FilterChip (secondaryContainer), card and top-bar elevation
// (surfaceTint) and the snackbar (inverseSurface) were all rendering baseline purple on top of a
// navy-and-crimson theme. Role names make an omission visible in review, and ThemeTest makes one
// fail the build.
//
// The base is deliberately neutral and stays that way. This is an app whose screens are mostly
// other people's cover art; chrome that competes with the artwork is what makes a media app look
// cheap, and a fixed neutral base is also what makes per-title accent colour safe — text contrast
// is guaranteed by the base, never by whatever hue a poster happens to yield.
//
// Three hues carry over from the old palette rather than being thrown away, but their roles are
// swapped so the loudest one is no longer the one shown most often:
//   primary   — warm gold, the fallback accent (was tertiary)
//   secondary — steel blue, structural: selected chips, the nav indicator
//   tertiary  — ember red, the crimson identity, used sparingly for badges
//   error     — rose red, pushed pink specifically so it cannot be mistaken for tertiary
//
// Every text-bearing pair below clears WCAG AA (4.5:1); ThemeTest asserts it rather than trusting
// this comment.

// ---------------------------------------------------------------------------------------------
// Dark — the default. Near-black with a faint cool cast, so posters read as the brightest thing
// on screen.
// ---------------------------------------------------------------------------------------------

val DarkPrimary = Color(0xFFF0C05A)
val DarkOnPrimary = Color(0xFF3A2A00)
val DarkPrimaryContainer = Color(0xFF5A4300)
val DarkOnPrimaryContainer = Color(0xFFFFDE9B)

val DarkSecondary = Color(0xFF9FC0EE)
val DarkOnSecondary = Color(0xFF12283F)
val DarkSecondaryContainer = Color(0xFF2B4460)
val DarkOnSecondaryContainer = Color(0xFFCFE0F7)

val DarkTertiary = Color(0xFFFF9E7D)
val DarkOnTertiary = Color(0xFF4A1500)
val DarkTertiaryContainer = Color(0xFF6B2408)
val DarkOnTertiaryContainer = Color(0xFFFFD6C6)

val DarkError = Color(0xFFFF8B9B)
val DarkOnError = Color(0xFF54000F)
val DarkErrorContainer = Color(0xFF7A0B22)
val DarkOnErrorContainer = Color(0xFFFFD9DE)

val DarkBackground = Color(0xFF0D0F13)
val DarkOnBackground = Color(0xFFE4E6EA)
val DarkSurface = Color(0xFF0D0F13)
val DarkOnSurface = Color(0xFFE4E6EA)
val DarkSurfaceVariant = Color(0xFF43474E)
val DarkOnSurfaceVariant = Color(0xFFC3C6CD)

val DarkOutline = Color(0xFF8D9199)
val DarkOutlineVariant = Color(0xFF43474E)

val DarkInverseSurface = Color(0xFFE4E6EA)
val DarkInverseOnSurface = Color(0xFF2A2C31)
val DarkInversePrimary = Color(0xFF7A5300)

// The surface-container family, new in Material 3 1.2 and previously unset here. These are what
// Card, BottomSheet, NavigationBar, Menu and SearchBar actually draw on — leaving them unset was
// most of why raised surfaces looked wrong.
val DarkSurfaceDim = Color(0xFF0D0F13)
val DarkSurfaceBright = Color(0xFF33353A)
val DarkSurfaceContainerLowest = Color(0xFF08090C)
val DarkSurfaceContainerLow = Color(0xFF15171B)
val DarkSurfaceContainer = Color(0xFF191B20)
val DarkSurfaceContainerHigh = Color(0xFF24262B)
val DarkSurfaceContainerHighest = Color(0xFF2F3136)

// ---------------------------------------------------------------------------------------------
// Light — warm off-white paper rather than clinical white, so the same gold and ember read as
// deliberate here instead of looking like a dark theme with the lights left on.
// ---------------------------------------------------------------------------------------------

val LightPrimary = Color(0xFF7A5300)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDE9B)
val LightOnPrimaryContainer = Color(0xFF2A1D00)

val LightSecondary = Color(0xFF2F5586)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD4E3F8)
val LightOnSecondaryContainer = Color(0xFF0E2946)

val LightTertiary = Color(0xFF9C3312)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD9C9)
val LightOnTertiaryContainer = Color(0xFF3A0E00)

val LightError = Color(0xFFB3172F)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFD9DE)
val LightOnErrorContainer = Color(0xFF430009)

val LightBackground = Color(0xFFFCFAF7)
val LightOnBackground = Color(0xFF1A1C1F)
val LightSurface = Color(0xFFFCFAF7)
val LightOnSurface = Color(0xFF1A1C1F)
val LightSurfaceVariant = Color(0xFFE3E1E6)
val LightOnSurfaceVariant = Color(0xFF46484D)

val LightOutline = Color(0xFF70717A)
val LightOutlineVariant = Color(0xFFC7C5CB)

val LightInverseSurface = Color(0xFF2F3135)
val LightInverseOnSurface = Color(0xFFF3F0EC)
val LightInversePrimary = Color(0xFFF0C05A)

val LightSurfaceDim = Color(0xFFDDD9D3)
val LightSurfaceBright = Color(0xFFFCFAF7)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F4F0)
val LightSurfaceContainer = Color(0xFFF2EEE9)
val LightSurfaceContainerHigh = Color(0xFFECE8E3)
val LightSurfaceContainerHighest = Color(0xFFE6E2DD)

// Shared by both schemes: the scrim behind a modal is black in every Material scheme, and its
// opacity — not its hue — is what does the work.
val ScrimBlack = Color(0xFF000000)
