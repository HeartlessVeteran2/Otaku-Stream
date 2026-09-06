package com.otakustream.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// The two gradients that make text legible over cover art.
//
// Both of these were copy-pasted at four call sites — the catalog grid tile, the home rail tile,
// the AniList rail tile and the media-details hero — and the app had grown seven different scrim
// alphas with no way to tell which were deliberate. Being in one place is worth more than the
// duplication saved: the ramp below is the kind of thing that gets tuned once and then has to be
// tuned again in every copy.
//
// The colour is the theme background, not black, so both work in either scheme: over a light
// theme this is a white wash under dark text, over a dark theme a black one under light text. The
// text drawn on top is onBackground either way.

// Bottom-anchored wash behind a title caption on a poster tile.
//
// Four stops, front-loaded, because of where this is actually drawn: every caller sizes the scrim
// box to the caption itself, not to the poster, so the box is only as tall as the text plus 8dp of
// padding. A straight transparent-to-opaque ramp — or even a midpoint at 40% — leaves the first
// line of a two-line title sitting on nearly clear artwork, which is exactly where a busy poster
// makes it unreadable. Reaching half opacity by 15% of the height puts the ramp above the text
// rather than through it.
//
// Tuned for that caption-sized box. Stretching this brush over a whole tile would read as a heavy
// wash over the artwork instead of a gradient; heroScrim() is the one for a tall box.
@Composable
@ReadOnlyComposable
fun posterScrim(): Brush {
    val base = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(
        0f to Color.Transparent,
        0.15f to base.copy(alpha = 0.5f),
        0.5f to base.copy(alpha = 0.8f),
        1f to base.copy(alpha = 0.95f),
    )
}

// Full-bleed wash over a detail-screen hero image, ending fully opaque so the artwork dissolves
// into the page rather than stopping at an edge. Starts its ramp later than posterScrim() because
// a hero is tall enough that an early ramp just dims the whole image.
//
// The midpoint carries a trace of the current title's accent — a tenth, which is enough that the
// hero of a red show and a blue one do not fade out through the same grey, and little enough that
// it never becomes a colour cast over the artwork. The bottom stop stays the pure background so
// the seam into the page below is exact whatever the accent is.
@Composable
@ReadOnlyComposable
fun heroScrim(): Brush {
    val base = MaterialTheme.colorScheme.background
    val tinted = lerp(base, LocalTitleAccent.current ?: base, ACCENT_IN_SCRIM)
    return Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to tinted.copy(alpha = 0.45f),
        1f to base,
    )
}

private const val ACCENT_IN_SCRIM = 0.10f
