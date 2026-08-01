package com.otakustream.feature.sources.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.library.DIRECT_PLAY_SOURCE_ID
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.ui.CoverImage
import com.otakustream.core.ui.EmptyState
import com.otakustream.feature.tracking.AniListListEntry
import com.otakustream.feature.tracking.AniListMedia

// The Stremio-style content home rendered on the Play tab. AniList discovery rails lead (Trending,
// This Season, All-Time Popular — no login needed), AnymeX-style; below them sit Continue Watching
// and the Popular/Latest rails fanned out across every installed source.
@Composable
fun HomeContent(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onPlayDirect: (url: String) -> Unit,
    onBrowseAddons: () -> Unit,
    onAniListClick: (mediaId: Long, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    aniListViewModel: AniListHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val aniListState by aniListViewModel.uiState.collectAsState()

    // AniList discovery couldn't load and there's nothing to show — surface it with a Retry
    // instead of leaving the top of the Play tab silently blank.
    val aniListRailsEmpty = aniListState.trending.isEmpty() && aniListState.thisSeason.isEmpty() &&
        aniListState.allTimePopular.isEmpty() && aniListState.continueWatching.isEmpty()

    // A LazyColumn, not a Column with verticalScroll. Both scroll; the difference is when the rails
    // compose. A scrolling Column composes all of them on the first frame, so opening the app
    // created all six rails — with maybe two on screen — before anything could be drawn. Each
    // LazyRow only composed its visible tiles, but the rail itself, its header and its measurement
    // were still work done up front. Lazily, the rails below the fold cost nothing until they are
    // scrolled to. Each rail is one item with its header, so a header can never be stranded on
    // screen without its row.
    LazyColumn(modifier = modifier.fillMaxSize()) {
        // ---- AniList discovery (works logged-out) ----
        if (aniListState.continueWatching.isNotEmpty()) {
            item(key = "anilist-continue") {
                RailHeader("Continue watching on AniList")
                AniListEntryRail(aniListState.continueWatching, onAniListClick)
            }
        }
        if (aniListState.trending.isNotEmpty()) {
            item(key = "anilist-trending") {
                RailHeader("Trending now")
                AniListMediaRail(aniListState.trending, onAniListClick)
            }
        }
        if (aniListState.thisSeason.isNotEmpty()) {
            item(key = "anilist-season") {
                RailHeader("Popular this season")
                AniListMediaRail(aniListState.thisSeason, onAniListClick)
            }
        }
        if (aniListState.allTimePopular.isNotEmpty()) {
            item(key = "anilist-popular") {
                RailHeader("All-time popular")
                AniListMediaRail(aniListState.allTimePopular, onAniListClick)
            }
        }
        if (aniListState.isLoading && !aniListState.hasLoadedOnce) {
            item(key = "anilist-loading") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        if (aniListState.error != null && aniListRailsEmpty && !aniListState.isLoading) {
            item(key = "anilist-error") { AniListRailsError(onRetry = aniListViewModel::refresh) }
        }

        // ---- Local history + source-based rails ----
        if (continueWatching.isNotEmpty()) {
            item(key = "local-continue") {
                RailHeader("Continue watching")
                LazyRow {
                    items(continueWatching, key = { "cw-${it.id}" }) { entry ->
                        ContinueWatchingTile(
                            entry = entry,
                            onClick = {
                                if (entry.sourceId == DIRECT_PLAY_SOURCE_ID) {
                                    onPlayDirect(entry.mediaUrl)
                                } else {
                                    onMediaClick(entry.sourceId, entry.mediaUrl, entry.mediaTitle)
                                }
                            },
                        )
                    }
                }
            }
        }

        when {
            !uiState.hasAnySources && uiState.hasLoadedOnce -> {
                if (continueWatching.isEmpty()) {
                    item(key = "no-sources") {
                        EmptyState(
                            icon = Icons.Filled.Extension,
                            title = "Nothing here yet",
                            message = "Install an add-on to fill your home with things to watch.",
                            actionLabel = "Browse add-ons",
                            onAction = onBrowseAddons,
                        )
                    }
                }
            }
            uiState.isLoading && !uiState.hasLoadedOnce -> {
                item(key = "sources-loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            else -> {
                if (uiState.popular.isNotEmpty()) {
                    item(key = "sources-popular") {
                        RailHeader("Popular")
                        CatalogRail(uiState.popular, onMediaClick)
                    }
                }
                if (uiState.latest.isNotEmpty()) {
                    item(key = "sources-latest") {
                        RailHeader("Latest")
                        CatalogRail(uiState.latest, onMediaClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun AniListRailsError(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Text(
            text = "Couldn't load AniList. Check your connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun RailHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AniListMediaRail(media: List<AniListMedia>, onAniListClick: (Long, String) -> Unit) {
    LazyRow {
        items(media, key = { "al-${it.id}" }) { item ->
            AniListPosterTile(
                title = item.displayTitle,
                coverUrl = item.coverImageUrl,
                subtitle = null,
                onClick = { onAniListClick(item.id, item.displayTitle) },
            )
        }
    }
}

@Composable
private fun AniListEntryRail(entries: List<AniListListEntry>, onAniListClick: (Long, String) -> Unit) {
    LazyRow {
        items(entries, key = { "ale-${it.media.id}" }) { entry ->
            val total = entry.media.episodes
            AniListPosterTile(
                title = entry.media.displayTitle,
                coverUrl = entry.media.coverImageUrl,
                subtitle = if (total != null) "Ep ${entry.progress}/$total" else "Ep ${entry.progress}",
                onClick = { onAniListClick(entry.media.id, entry.media.displayTitle) },
            )
        }
    }
}

@Composable
private fun CatalogRail(entries: List<CatalogEntry>, onMediaClick: (Long, String, String) -> Unit) {
    LazyRow {
        items(entries, key = { "${it.sourceId}:${it.media.url}" }) { entry ->
            PosterTile(
                title = entry.media.title,
                coverUrl = entry.media.coverUrl,
                onClick = { onMediaClick(entry.sourceId, entry.media.url, entry.media.title) },
            )
        }
    }
}

@Composable
private fun ContinueWatchingTile(entry: WatchHistoryEntry, onClick: () -> Unit) {
    PosterTile(title = entry.mediaTitle, coverUrl = entry.coverUrl, onClick = onClick)
}

// Same poster-box treatment as the Catalog grid's MediaCard, sized for a horizontal rail.
@Composable
private fun PosterTile(title: String, coverUrl: String?, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .padding(start = 16.dp)
            .width(120.dp)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick),
    ) {
        CoverImage(
            url = coverUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
                    ),
                )
                .padding(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
