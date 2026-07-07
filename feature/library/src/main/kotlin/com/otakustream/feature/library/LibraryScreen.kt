package com.otakustream.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.library.WatchHistoryEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Watchlist") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History") })
        }

        when (selectedTab) {
            0 -> WatchlistTab(uiState, viewModel, onMediaClick)
            else -> HistoryTab(uiState, viewModel, onMediaClick)
        }
    }
}

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

        item {
            Text(
                text = "Watchlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (uiState.watchlist.isEmpty()) {
            item {
                Text(
                    text = "Nothing saved yet — add something from its details page.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(uiState.watchlist, key = { it.mediaUrl }) { entry ->
            ListItem(
                headlineContent = { Text(entry.title) },
                trailingContent = {
                    IconButton(onClick = { viewModel.removeFromWatchlist(entry.mediaUrl) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                },
                modifier = Modifier.clickable { onMediaClick(entry.sourceId, entry.mediaUrl, entry.title) },
            )
        }
    }
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
        supportingContent = {
            val formattedDate = remember(entry.watchedAtEpochMs) {
                DateFormat.getDateTimeInstance().format(Date(entry.watchedAtEpochMs))
            }
            Text("${entry.episodeName} · $formattedDate")
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
