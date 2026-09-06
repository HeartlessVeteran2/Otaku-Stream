package com.otakustream.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Poster/cover thumbnail with a graceful fallback: shows the image when we have a URL and it loads,
// otherwise a placeholder film icon on the surface-variant background. Shared across every feature
// module (catalog grids, detail heroes, library rows) so the fallback behaves identically everywhere.
@Composable
fun CoverImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    var isError by remember(url) { mutableStateOf(false) }
    var isLoaded by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    val hasUrl = !url.isNullOrBlank() && !isError

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (hasUrl) {
            // Crossfade so a cache miss fades in over the placeholder instead of popping. The
            // request is remembered on url so scrolling a grid doesn't rebuild it every recomposition.
            AsyncImage(
                model = remember(url, context) {
                    ImageRequest.Builder(context).data(url).crossfade(CROSSFADE_MS).build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { isError = true },
                onSuccess = { isLoaded = true },
            )
            // A poster that hasn't arrived yet was a flat surface-variant rectangle, so scrolling a
            // grid on a slow connection read as a wall of empty boxes with no sign anything was
            // coming. A slow pulse says "loading" without pretending to be content — deliberately
            // not a sweeping highlight, which at rail speed looks like glare moving across the
            // screen. It only draws until the image lands, so a warm cache never shows it.
            if (!isLoaded) {
                ShimmerFill(modifier = Modifier.matchParentSize())
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ShimmerFill(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "coverShimmer")
    val alpha by transition.animateFloat(
        initialValue = SHIMMER_MIN_ALPHA,
        targetValue = SHIMMER_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_PERIOD_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coverShimmerAlpha",
    )
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        ),
    )
}

private const val CROSSFADE_MS = 180

// Faint: this is a stand-in for artwork, and anything stronger competes with the posters that have
// already loaded around it.
private const val SHIMMER_MIN_ALPHA = 0.04f
private const val SHIMMER_MAX_ALPHA = 0.12f
private const val SHIMMER_PERIOD_MS = 900
