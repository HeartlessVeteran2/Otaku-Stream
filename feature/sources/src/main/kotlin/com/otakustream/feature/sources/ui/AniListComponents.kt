package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.otakustream.core.ui.PosterTile

// AniList rails hand the shared tile a subtitle line: progress, a relation label, or a countdown
// to the next episode. Kept as a named wrapper rather than inlining PosterTile at each of the eight
// call sites, because the rail width belongs with the rail, not with every caller.
@Composable
fun AniListPosterTile(
    title: String,
    coverUrl: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PosterTile(
        title = title,
        coverUrl = coverUrl,
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier.padding(start = 16.dp).width(120.dp),
    )
}
