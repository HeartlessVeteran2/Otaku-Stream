package com.otakustream.feature.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING
import com.otakustream.core.sources.api.Video
import com.otakustream.feature.tracking.LinkAniListDialog

@Composable
fun MediaDetailsScreen(
    sourceId: Long,
    mediaUrl: String,
    mediaTitle: String,
    onPlayVideo: (videoUrl: String) -> Unit,
    onOpenTracking: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val inLibrary by viewModel.inLibrary.collectAsState()
    val libraryStatus by viewModel.libraryStatus.collectAsState()
    val watchedEpisodeUrls by viewModel.watchedEpisodeUrls.collectAsState()
    val trackerLink by viewModel.trackerLink.collectAsState()
    val hasTrackerToken by viewModel.hasTrackerToken.collectAsState()
    val autoPlayEnabled by viewModel.autoPlayEnabled.collectAsState()
    var showLinkDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sourceId, mediaUrl) {
        viewModel.load(sourceId, mediaUrl, mediaTitle)
    }

    LaunchedEffect(uiState.resolvedVideoUrl) {
        uiState.resolvedVideoUrl?.let {
            onPlayVideo(it)
            viewModel.consumeResolvedVideoUrl()
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(280.dp).clip(MaterialTheme.shapes.large)) {
                CoverImage(
                    url = uiState.details?.backgroundUrl ?: uiState.details?.media?.coverUrl,
                    contentDescription = mediaTitle,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                ) {
                    Text(
                        text = mediaTitle,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground,
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = viewModel::toggleWatchlist,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                        ),
                    ) {
                        Icon(
                            imageVector = if (inLibrary) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (inLibrary) "Remove from watchlist" else "Add to watchlist",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Watch-status selector for a saved title — moves it between the Library's buckets.
            if (inLibrary) {
                LibraryStatusRow(
                    status = libraryStatus,
                    onSelect = viewModel::setLibraryStatus,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            trackerLink?.let { link ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AniList: ${link.trackerTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = viewModel::unlinkTracker) { Text("Unlink") }
                }
            } ?: if (hasTrackerToken) {
                TextButton(onClick = { showLinkDialog = true }) { Text("Link to AniList") }
            } else {
                // Not signed in — a link dialog would only fail, so make this a tappable shortcut
                // straight to AniList sign-in instead of plain text telling the user to hunt for it.
                TextButton(onClick = onOpenTracking) { Text("Sign in to AniList to track this show") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(text = "Auto-play next episode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoPlayEnabled, onCheckedChange = viewModel::setAutoPlayEnabled)
            }

            if (uiState.isLoading && uiState.details == null) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            uiState.details?.let { details ->
                if (details.imdbRating != null || details.runtime != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        details.imdbRating?.let { rating ->
                            Text(
                                text = "★ $rating",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        details.runtime?.let { runtime ->
                            Text(text = runtime, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            uiState.details?.description?.let { description ->
                Text(text = description, modifier = Modifier.padding(top = 8.dp))
            }

            uiState.details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                Text(
                    text = "Cast: ${cast.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            uiState.error?.let { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::retryLoad) { Text("Retry") }
                }
            }

            val seasons = remember(uiState.episodes) { uiState.episodes.mapNotNull { it.season }.distinct().sorted() }
            var selectedSeason by remember(uiState.episodes) { mutableStateOf(seasons.firstOrNull()) }
            val visibleEpisodes = remember(uiState.episodes, selectedSeason) {
                if (seasons.isEmpty()) uiState.episodes else uiState.episodes.filter { it.season == selectedSeason }
            }

            if (seasons.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(top = 16.dp)) {
                    items(seasons, key = { it }) { season ->
                        FilterChip(
                            selected = season == selectedSeason,
                            onClick = { selectedSeason = season },
                            label = { Text("Season $season") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            val watchedCount = remember(visibleEpisodes, watchedEpisodeUrls) {
                visibleEpisodes.count { it.url in watchedEpisodeUrls }
            }
            if (watchedCount > 0) {
                Text(
                    text = "$watchedCount of ${visibleEpisodes.size} watched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!uiState.isLoading && visibleEpisodes.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Movie,
                    title = "No episodes listed",
                    message = "This source didn't return anything to play for this title.",
                )
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(visibleEpisodes, key = { it.url }) { episode ->
                    val watched = episode.url in watchedEpisodeUrls
                    val resolving = episode.url == uiState.resolvingEpisodeUrl
                    // While any episode is resolving, block taps so a second tap can't start a
                    // competing resolve (or re-trigger the one in flight).
                    val rowEnabled = uiState.resolvingEpisodeUrl == null
                    ListItem(
                        headlineContent = {
                            Text(
                                text = episode.name,
                                // Watched episodes recede so the next unwatched one stands out.
                                color = if (watched) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        trailingContent = when {
                            resolving -> {
                                {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            watched -> {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Watched",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            else -> null
                        },
                        modifier = Modifier.clickable(enabled = rowEnabled) {
                            viewModel.playEpisode(sourceId, episode)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (showLinkDialog) {
        LinkAniListDialog(
            mediaUrl = mediaUrl,
            defaultQuery = mediaTitle,
            onDismiss = { showLinkDialog = false },
        )
    }

    if (uiState.pendingVideoChoices.isNotEmpty()) {
        StreamPickerSheet(
            choices = uiState.pendingVideoChoices,
            onSelect = viewModel::selectVideo,
            onDismiss = viewModel::dismissVideoPicker,
        )
    }
}

@Composable
private fun LibraryStatusRow(
    status: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        LIBRARY_STATUS_PLANNED to "Plan to watch",
        LIBRARY_STATUS_WATCHING to "Watching",
        LIBRARY_STATUS_COMPLETED to "Completed",
    )
    // Default a saved-but-unmigrated row (null → treated as PLANNED) so one chip always reads active.
    val current = status ?: LIBRARY_STATUS_PLANNED
    LazyRow(modifier = modifier) {
        items(options, key = { it.first }) { (value, label) ->
            FilterChip(
                selected = value == current,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamPickerSheet(choices: List<Video>, onSelect: (Video) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            item {
                Text(text = "Pick a quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            itemsIndexed(choices) { index, video ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(video) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = false, onClick = { onSelect(video) })
                    Text(text = prettyQuality(video.quality, index))
                }
            }
        }
    }
}

// Source quality strings are free-form (often "720p", sometimes a filename or opaque token).
// Trim/tidy it; fall back to a simple ordinal when it's blank or unhelpfully long.
private fun prettyQuality(raw: String, index: Int): String {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() || trimmed.length > 40 -> "Stream ${index + 1}"
        else -> trimmed
    }
}
