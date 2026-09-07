package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// The first thing someone with no sources installed should see, and for a long time the last thing
// they did.
//
// The prompt used to be an EmptyState emitted *after* the AniList rails — roughly 660dp of posters
// down, off the bottom of every phone — and it was suppressed entirely when Continue Watching had
// anything in it, so a user with watch history and no sources never saw it at all. The app looked
// like it worked: AniList discovery fills the screen with covers whether or not a single source is
// installed, and tapping one just fails to find anywhere to play it.
//
// A compact card rather than a full EmptyState, because the AniList rails below it are real,
// useful content that works logged-out. This says its piece in about a quarter of the height and
// gets out of the way.
//
// Two buttons, not one. Stremio add-ons and Mangayomi/AnymeX JavaScript extensions are separate
// ecosystems with separate directories, and every entry point in the app offered only the first —
// which is why the app read as Stremio-only to someone looking for anime extensions.
@Composable
fun NoSourcesBanner(
    onBrowseAddons: () -> Unit,
    onBrowseExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No sources installed yet", style = MaterialTheme.typography.titleSmall)
                Text(
                    // "below", because this card is the first item in the list — an earlier draft
                    // said "above" and described nothing, which is worse than saying nothing at
                    // all. What it points at is the AniList rails underneath: covers, titles and
                    // schedules that render perfectly with no sources installed and cannot play
                    // anything on their own. Nothing else in the app says where episodes come from.
                    "The rails below are AniList browsing. Playing an episode needs at least one " +
                        "add-on or extension installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilledTonalButton(onClick = onBrowseAddons) { Text("Add-ons") }
                    TextButton(onClick = onBrowseExtensions) { Text("Extensions") }
                }
            }
        }
    }
}
