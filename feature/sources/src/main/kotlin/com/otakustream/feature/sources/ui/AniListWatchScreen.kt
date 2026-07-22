package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// Step between an AniList anime and an actual stream: the user picks the matching result from their
// installed sources, then the app hands off to the normal source detail/playback flow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListWatchScreen(
    onBack: () -> Unit,
    onBrowseAddons: () -> Unit,
    onOpenSource: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AniListWatchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // When the bridge resolves a target (an existing mapping, or a fresh pick), hand off.
    LaunchedEffect(uiState.navigateTo) {
        uiState.navigateTo?.let { target ->
            onOpenSource(target.sourceId, target.mediaUrl, target.title)
            viewModel.consumeNavigation()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Find a source") },
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
                onValueChange = viewModel::search,
                label = { Text("Search your sources") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            when {
                uiState.hasNoSources -> EmptyState(
                    icon = Icons.Filled.Extension,
                    title = "No sources installed",
                    message = "Install an add-on or extension, then come back to watch this from AniList.",
                    actionLabel = "Browse add-ons",
                    onAction = onBrowseAddons,
                )
                uiState.isSearching -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) { CircularProgressIndicator() }
                uiState.query.isBlank() -> CenterText(
                    "Type a title above to find it in your installed sources.",
                )
                uiState.groups.isEmpty() -> CenterText(
                    "No matches. Try a different spelling or the romaji title.",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    uiState.groups.forEach { group ->
                        item(key = "hdr-${group.sourceId}") {
                            Text(
                                text = group.sourceName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group.items, key = { "${group.sourceId}:${it.url}" }) { item ->
                            ListItem(
                                headlineContent = { Text(item.title) },
                                leadingContent = {
                                    CoverImage(
                                        url = item.coverUrl,
                                        contentDescription = item.title,
                                        modifier = Modifier.padding(4.dp),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.pick(group.sourceId, item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
