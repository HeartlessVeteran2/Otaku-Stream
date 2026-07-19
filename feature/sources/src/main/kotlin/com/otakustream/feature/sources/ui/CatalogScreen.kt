package com.otakustream.feature.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.api.SourceFilter

private const val LOAD_MORE_THRESHOLD_ITEMS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String) -> Unit,
    onManageSourcesClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - LOAD_MORE_THRESHOLD_ITEMS
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Catalog") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.tertiary,
                ),
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

            if (uiState.availableFilters.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp)) {
                    items(uiState.availableFilters, key = { it.name }) { filter ->
                        val selected = uiState.selectedFilters.firstOrNull { it.name == filter.name }
                        FilterChooserChip(
                            filter = filter,
                            selectedValue = selected?.values?.getOrNull(selected.selected),
                            onSelect = { valueIndex -> viewModel.selectFilter(filter, valueIndex) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.tertiary)
            }

            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 120.dp), state = gridState) {
                items(uiState.entries, key = { "${it.sourceId}:${it.media.url}" }) { entry ->
                    MediaCard(
                        title = entry.media.title,
                        coverUrl = entry.media.coverUrl,
                        onClick = { onMediaClick(entry.sourceId, entry.media.url, entry.media.title) },
                    )
                }
                if (uiState.isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChooserChip(
    filter: SourceFilter,
    selectedValue: String?,
    onSelect: (valueIndex: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = selectedValue != null,
            onClick = { expanded = true },
            label = { Text(selectedValue ?: filter.name) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
            ),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Some Stremio catalogs declare huge option lists (e.g. a "year" filter spanning a
            // century) — a plain, non-lazy DropdownMenu would instantiate every item at once.
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                if (selectedValue != null) {
                    item { DropdownMenuItem(text = { Text("All") }, onClick = { onSelect(null); expanded = false }) }
                }
                itemsIndexed(filter.values) { index, value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(index); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun MediaCard(title: String, coverUrl: String?, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
