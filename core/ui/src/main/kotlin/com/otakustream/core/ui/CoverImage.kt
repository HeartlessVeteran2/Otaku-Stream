package com.otakustream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val isError = remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank() && !isError.value) {
            // Crossfade so a cache miss fades in over the placeholder instead of popping. The
            // request is remembered on url so scrolling a grid doesn't rebuild it every recomposition.
            AsyncImage(
                model = remember(url, context) {
                    ImageRequest.Builder(context).data(url).crossfade(CROSSFADE_MS).build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { isError.value = true },
            )
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

private const val CROSSFADE_MS = 180
