package com.otakustream.feature.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.library.DIRECT_PLAY_SOURCE_ID
import com.otakustream.core.database.library.WatchHistoryEntry

// The Stremio-style content home rendered on the Play tab: Continue Watching plus Popular and
// Latest rails fanned out across every enabled source.
@Composable
fun HomeContent(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onPlayDirect: (url: String) -> Unit,
    onBrowseAddons: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (continueWatching.isNotEmpty()) {
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

        when {
            !uiState.hasAnySources && uiState.hasLoadedOnce -> {
                if (continueWatching.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Extension,
                        title = "Nothing here yet",
                        message = "Install an add-on to fill your home with things to watch.",
                        actionLabel = "Browse add-ons",
                        onAction = onBrowseAddons,
                    )
                }
            }
            uiState.isLoading && !uiState.hasLoadedOnce -> {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            else -> {
                if (uiState.popular.isNotEmpty()) {
                    RailHeader("Popular")
                    CatalogRail(uiState.popular, onMediaClick)
                }
                if (uiState.latest.isNotEmpty()) {
                    RailHeader("Latest")
                    CatalogRail(uiState.latest, onMediaClick)
                }
            }
        }
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
                .align(androidx.compose.ui.Alignment.BottomCenter)
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
