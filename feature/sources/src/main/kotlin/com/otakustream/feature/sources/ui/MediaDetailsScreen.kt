package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.api.Video
import com.otakustream.feature.tracking.LinkAniListDialog

@Composable
fun MediaDetailsScreen(
    sourceId: Long,
    mediaUrl: String,
    mediaTitle: String,
    onPlayVideo: (videoUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val inLibrary by viewModel.inLibrary.collectAsState()
    val trackerLink by viewModel.trackerLink.collectAsState()
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
            CoverImage(
                url = uiState.details?.backgroundUrl ?: uiState.details?.media?.coverUrl,
                contentDescription = mediaTitle,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(
                    text = mediaTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::toggleWatchlist) {
                    Icon(
                        imageVector = if (inLibrary) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (inLibrary) "Remove from watchlist" else "Add to watchlist",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
            } ?: TextButton(onClick = { showLinkDialog = true }) { Text("Link to AniList") }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(text = "Auto-play next episode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoPlayEnabled, onCheckedChange = viewModel::setAutoPlayEnabled)
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            uiState.details?.let { details ->
                if (details.imdbRating != null || details.runtime != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        details.imdbRating?.let { rating ->
                            Text(text = "★ $rating", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp))
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
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            val seasons = remember(uiState.episodes) { uiState.episodes.mapNotNull { it.season }.distinct().sorted() }
            var selectedSeason by remember(uiState.episodes) { mutableStateOf(seasons.firstOrNull()) }
            val visibleEpisodes = if (seasons.isEmpty()) uiState.episodes else uiState.episodes.filter { it.season == selectedSeason }

            if (seasons.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(top = 16.dp)) {
                    items(seasons, key = { it }) { season ->
                        FilterChip(
                            selected = season == selectedSeason,
                            onClick = { selectedSeason = season },
                            label = { Text("Season $season") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(visibleEpisodes, key = { it.url }) { episode ->
                    ListItem(
                        headlineContent = { Text(episode.name) },
                        modifier = Modifier.clickable { viewModel.playEpisode(sourceId, episode) },
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamPickerSheet(choices: List<Video>, onSelect: (Video) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Choose a stream", style = MaterialTheme.typography.titleMedium)
            choices.forEach { video ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(video) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = false, onClick = { onSelect(video) })
                    Text(text = video.quality)
                }
            }
        }
    }
}
