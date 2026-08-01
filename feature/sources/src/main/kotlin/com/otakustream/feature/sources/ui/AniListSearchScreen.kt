package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// Search AniList's catalog directly, then open a title's AniList detail (and Watch from there).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListSearchScreen(
    onBack: () -> Unit,
    onOpenAniList: (mediaId: Long, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AniListSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Search AniList") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search anime") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            when {
                uiState.isSearching -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) { CircularProgressIndicator() }
                // A failed search was a dead end: the message sat there and the only way to try
                // again was to edit the query, which is not what the user wants to change. Almost
                // every failure here is a dropped connection, so offer the one action that fixes it.
                uiState.error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                ) {
                    Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::retry) { Text("Try again") }
                }
                uiState.results.isEmpty() && uiState.query.isBlank() -> CenterMessage(
                    "Search for an anime to get started.",
                )
                uiState.results.isEmpty() -> CenterMessage(
                    "No results for “${uiState.query}”. Try another spelling.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                ) {
                    items(uiState.results, key = { it.id }) { media ->
                        AniListPosterTile(
                            title = media.displayTitle,
                            coverUrl = media.coverImageUrl,
                            subtitle = null,
                            onClick = { onOpenAniList(media.id, media.displayTitle) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
