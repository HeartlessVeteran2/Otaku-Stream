package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.otakustream.core.ui.PosterTile

// AniList rails hand the shared tile a subtitle line: progress, a relation label, or a countdown
// to the next episode. Kept as a named wrapper rather than inlining PosterTile at each of the eight
// call sites, because the rail width belongs with the rail, not with every caller.
//
// Width only — no start padding. The tile used to add 16dp itself, which is fine for a bare LazyRow
// and wrong for one that already sets contentPadding: the two AniList detail rails do, so their
// first tile sat 32dp in and every gap was 16dp rather than the intended spacing. Spacing belongs to
// the row, which is the only thing that knows whether it has edges to inset from.
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
        modifier = modifier.width(RailTileWidth),
    )
}
