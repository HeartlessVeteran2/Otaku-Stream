package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onManageSourcesClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Otaku Stream") },
                actions = {
                    IconButton(onClick = onManageSourcesClick) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Manage sources")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::search,
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 120.dp)) {
                items(uiState.entries, key = { "${it.sourceId}:${it.media.url}" }) { entry ->
                    MediaCard(
                        title = entry.media.title,
                        coverUrl = entry.media.coverUrl,
                        onClick = { onMediaClick(entry.sourceId, entry.media.url, entry.media.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCard(title: String, coverUrl: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        CoverImage(
            url = coverUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
