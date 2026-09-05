package com.otakustream.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
// Three stops, not two. A straight transparent-to-opaque ramp puts most of its opacity in the
// bottom fifth, which leaves the first line of a two-line title sitting on almost-clear art and
// unreadable against a busy poster. Pushing the mid-stop up to 55% at 40% of the height covers the
// text and still fades out before the ramp becomes a visible band.
@Composable
@ReadOnlyComposable
fun posterScrim(): Brush {
    val base = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(
        0f to Color.Transparent,
        0.4f to base.copy(alpha = 0.55f),
        1f to base.copy(alpha = 0.92f),
    )
}

// Full-bleed wash over a detail-screen hero image, ending fully opaque so the artwork dissolves
// into the page rather than stopping at an edge. Starts its ramp later than posterScrim() because
// a hero is tall enough that an early ramp just dims the whole image.
@Composable
@ReadOnlyComposable
fun heroScrim(): Brush {
    val base = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to base.copy(alpha = 0.45f),
        1f to base,
    )
}
