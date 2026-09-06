package com.otakustream.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The poster tile, once.
//
// There were four of these: the catalog grid's MediaCard, the home rail's PosterTile, the AniList
// rail's AniListPosterTile and an inline one in the Stremio account screen. Three were the same
// 2:3 box with the same border, shape and scrim, differing only in what they hung on top; the
// fourth had drifted to a different corner radius, no border and no scrim, so the same library
// looked like two different apps depending on which screen you were on. Two of the three even said
// in a comment that they were copies of each other.
//
// The differences that were real are parameters: a subtitle line for progress and countdowns, a
// badge slot for the catalog's save button and source chip, and the title's text style, which is a
// size larger in the grid than in a 120dp rail.
@Composable
fun PosterTile(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle? = null,
    // Drawn over the artwork, above the scrim. BoxScope so a badge can align itself to a corner.
    badges: @Composable BoxScope.() -> Unit = {},
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .aspectRatio(POSTER_ASPECT)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            // One accessible node for the whole tile. Without this a tile is three: the poster, the
            // title, and the subtitle — so TalkBack read every rail entry's title twice (the image
            // was labelled with it, and so was the caption underneath) and then read "Ep 5/12" as a
            // separate item with nothing saying which show it belonged to.
            .semantics(mergeDescendants = true) {},
    ) {
        // Null, not the title. The caption below is a text node of its own, so labelling the image
        // too made every tile announce its title twice — once for the image and once for the text
        // it sits under. The caption is the one that stays, because it is also what a sighted user
        // reads.
        CoverImage(url = coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
        badges()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(posterScrim())
                .padding(8.dp),
        ) {
            Text(
                text = title,
                style = titleStyle ?: MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Poster proportions. Every source's artwork is drawn to this, and a tile that isn't gets letterbox
// bars of whatever is behind it.
private const val POSTER_ASPECT = 2f / 3f
