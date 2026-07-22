package com.otakustream.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.net.Uri
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.otakustream.core.database.library.DIRECT_PLAY_SOURCE_ID
import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.Video
import com.otakustream.feature.library.local.LocalVideosViewModel
import com.otakustream.feature.library.local.findSidecarSubtitles
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onPlayDirect: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Direct plays (local files, pasted links) have no details page — route them straight back
    // into the player; catalog entries open their details as before.
    val onEntryClick: (Long, String, String) -> Unit = { sourceId, mediaUrl, title ->
        if (sourceId == DIRECT_PLAY_SOURCE_ID) onPlayDirect(mediaUrl) else onMediaClick(sourceId, mediaUrl, title)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Watchlist") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("On device") })
        }

        when (selectedTab) {
            0 -> WatchlistTab(uiState, viewModel, onEntryClick)
            1 -> HistoryTab(uiState, viewModel, onEntryClick)
            else -> OnDeviceTab(onPlayDirect)
        }
    }
}

// Watchlist status buckets, in the order they're shown.
private val LIBRARY_STATUS_SECTIONS = listOf(
    LIBRARY_STATUS_WATCHING to "Watching",
    LIBRARY_STATUS_PLANNED to "Plan to watch",
    LIBRARY_STATUS_COMPLETED to "Completed",
)

@Composable
private fun WatchlistTab(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onMediaClick: (Long, String, String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (uiState.continueWatching.isNotEmpty()) {
            item {
                Text(
                    text = "Continue watching",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(uiState.continueWatching, key = { "cw-${it.id}" }) { entry ->
                HistoryRow(entry) { onMediaClick(entry.sourceId, entry.mediaUrl, entry.mediaTitle) }
            }
        }

        if (uiState.watchlist.isEmpty()) {
            item {
                Text(
                    text = "Watchlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                Text(
                    text = "Nothing saved yet — tap the bookmark on a title, or on a catalog poster.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // One section per non-empty status bucket. Unmigrated rows (status not one of the known
        // values) fall back into "Plan to watch" so nothing is ever hidden.
        val byStatus = uiState.watchlist.groupBy { entry ->
            if (LIBRARY_STATUS_SECTIONS.any { it.first == entry.status }) entry.status else LIBRARY_STATUS_PLANNED
        }
        LIBRARY_STATUS_SECTIONS.forEach { (status, label) ->
            val entries = byStatus[status].orEmpty()
            if (entries.isNotEmpty()) {
                item(key = "hdr-$status") {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(entries, key = { it.mediaUrl }) { entry ->
                    WatchlistRow(
                        title = entry.title,
                        coverUrl = entry.coverUrl,
                        currentStatus = entry.status,
                        onSetStatus = { viewModel.setStatus(entry.mediaUrl, it) },
                        onRemove = { viewModel.removeFromWatchlist(entry.mediaUrl) },
                        onClick = { onMediaClick(entry.sourceId, entry.mediaUrl, entry.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchlistRow(
    title: String,
    coverUrl: String?,
    currentStatus: String,
    onSetStatus: (String) -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            CoverImage(
                url = coverUrl,
                contentDescription = title,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Change status")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    LIBRARY_STATUS_SECTIONS.forEach { (status, label) ->
                        DropdownMenuItem(
                            text = { Text(if (status == currentStatus) "$label ✓" else label) },
                            onClick = { onSetStatus(status); menuExpanded = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = { onRemove(); menuExpanded = false },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun HistoryTab(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onMediaClick: (Long, String, String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (uiState.history.isNotEmpty()) {
            item {
                TextButton(onClick = viewModel::clearHistory, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Clear history")
                }
            }
        } else {
            item {
                Text(
                    text = "No watch history yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(uiState.history, key = { it.id }) { entry ->
            HistoryRow(entry) { onMediaClick(entry.sourceId, entry.mediaUrl, entry.mediaTitle) }
        }
    }
}

@Composable
private fun HistoryRow(entry: WatchHistoryEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.mediaTitle) },
        leadingContent = {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.mediaTitle,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
            )
        },
        supportingContent = {
            val formattedDate = remember(entry.watchedAtEpochMs) {
                DateFormat.getDateTimeInstance().format(Date(entry.watchedAtEpochMs))
            }
            // Direct plays have no episode name — show just the date instead of " · date".
            Text(if (entry.episodeName.isBlank()) formattedDate else "${entry.episodeName} · $formattedDate")
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun OnDeviceTab(
    onPlayDirect: (url: String) -> Unit,
    viewModel: LocalVideosViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    when {
        !uiState.hasPermission -> {
            EmptyState(
                icon = Icons.Filled.VideoFile,
                title = "See your videos here",
                message = "Allow access to your device's videos to browse and play them.",
                actionLabel = "Allow access",
                onAction = { permissionLauncher.launch(viewModel.requiredPermission) },
            )
        }
        uiState.isLoading && !uiState.hasLoadedOnce -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        uiState.videos.isEmpty() -> {
            EmptyState(
                icon = Icons.Filled.VideoFile,
                title = "No videos found",
                message = "Videos on this device will show up here.",
            )
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.videos, key = { it.id }) { video ->
                    ListItem(
                        headlineContent = { Text(video.displayName) },
                        leadingContent = {
                            LocalVideoThumbnail(
                                uri = video.uri,
                                contentDescription = video.displayName,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        },
                        supportingContent = {
                            val duration = formatDurationMs(video.durationMs)
                            Text(if (video.bucketName.isBlank()) duration else "${video.bucketName} · $duration")
                        },
                        modifier = Modifier.clickable {
                            val url = video.uri.toString()
                            // VLC-style sidecar subtitles: hand any same-basename .srt/.ass/.ssa/.vtt
                            // next to the file to the player. historyHandled = false keeps the
                            // player recording this as a direct play as usual.
                            val sidecars = findSidecarSubtitles(video.dataPath)
                            if (sidecars.isNotEmpty()) {
                                PendingPlayback.stash(
                                    Video(url = url, quality = "", subtitleTracks = sidecars),
                                    historyHandled = false,
                                )
                            }
                            onPlayDirect(url)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalVideoThumbnail(
    uri: Uri,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Decoder set per-request so the global ImageLoader (shared cache + thread pool) is reused
    // rather than spinning up a dedicated one for video frames.
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .decoderFactory(VideoFrameDecoder.Factory())
            .videoFrameMillis(1000)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
