package com.otakustream.feature.sources.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.otakustream.core.ui.EmptyState
import com.otakustream.core.ui.PosterTile
import com.otakustream.core.ui.PullablePlaceholder
import com.otakustream.core.ui.RefreshableBox
import com.otakustream.feature.sources.SourceFailure
import com.otakustream.feature.sources.allOffline
import com.otakustream.feature.sources.describe
import com.otakustream.feature.sources.headline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.api.SourceFilter

private const val LOAD_MORE_THRESHOLD_ITEMS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String, coverUrl: String?) -> Unit,
    onManageSourcesClick: () -> Unit,
    onBrowseAddons: () -> Unit,
    onBrowseExtensions: () -> Unit,
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
                // "Browse", matching the tab that gets here. The tab said Browse and the screen
                // said Catalog, which reads as two different places — and "catalog" is Stremio's
                // internal word for a source's listing, not a name for this screen.
                title = { Text("Browse") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.tertiary,
                ),
                actions = {
                    IconButton(onClick = onManageSourcesClick) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Manage add-ons & sources")
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

            // Source picker: scope browsing/search to one source (AnymeX-style) or "All".
            if (uiState.availableSources.size > 1) {
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.selectedSourceId == null,
                            onClick = { viewModel.selectSource(null) },
                            label = { Text("All sources") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    items(uiState.availableSources, key = { it.id }) { source ->
                        FilterChip(
                            selected = uiState.selectedSourceId == source.id,
                            onClick = { viewModel.selectSource(source.id) },
                            label = { Text(source.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }

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

            if (uiState.failures.isNotEmpty()) {
                SourceErrorBanner(
                    failures = uiState.failures,
                    onRetry = viewModel::retry,
                    onDismiss = viewModel::dismissSourceError,
                )
            }

            val stillLoadingFirstPage = uiState.entries.isEmpty() && (uiState.isLoading || !uiState.hasLoadedOnce)
            // The pull gesture, over the results area only. Above it sit the search field and the
            // filter chips, which do not scroll with the grid and must not slide under an
            // indicator the user is dragging.
            RefreshableBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    stillLoadingFirstPage -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    // Both empty states are wrapped, because an empty state is exactly where pulling
                    // to reload is the only thing left to do — and an EmptyState on its own does not
                    // scroll, so the gesture would not reach the box above it. PullablePlaceholder
                    // gives it the scroll container the drag travels through.
                    uiState.entries.isEmpty() && !uiState.hasAnySources -> {
                        PullablePlaceholder {
                            EmptyState(
                                icon = Icons.Filled.Extension,
                                title = "No sources yet",
                                // Both ecosystems, because offering only one implies the other isn't there.
                                message = "Install a Stremio add-on or a Mangayomi/AnymeX extension, and " +
                                    "this fills up with things to watch.",
                                actionLabel = "Browse add-ons",
                                onAction = onBrowseAddons,
                                secondaryActionLabel = "Browse extensions",
                                onSecondaryAction = onBrowseExtensions,
                            )
                        }
                    }
                    uiState.entries.isEmpty() -> {
                        PullablePlaceholder {
                            EmptyState(
                                icon = Icons.Filled.SearchOff,
                                title = "No matches",
                                message = "Nothing here for that search. Try a different title or clear your filters.",
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(uiState.entries, key = { "${it.sourceId}:${it.media.url}" }) { entry ->
                                MediaCard(
                                    title = entry.media.title,
                                    coverUrl = entry.media.coverUrl,
                                    // Only badge the source when browsing All — in a scoped view the
                                    // picker already shows which source you're in.
                                    sourceName = if (uiState.selectedSourceId == null) uiState.sourceNames[entry.sourceId] else null,
                                    saved = entry.media.url in uiState.savedMediaUrls,
                                    onToggleSave = { viewModel.toggleSave(entry) },
                                    onClick = {
                                        onMediaClick(
                                            entry.sourceId,
                                            entry.media.url,
                                            entry.media.title,
                                            entry.media.coverUrl,
                                        )
                                    },
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
        }
    }
}

// Collapsed by default: the common case is one flaky source and the user only wants the grid back.
// Expanding names each source and what it said, which is the difference between "retry" and
// "uninstall this add-on" — a decision the old bare count could not support.
//
// When every source failed the same device-level way there is nothing per-source worth listing, so
// the cause replaces the headline instead of being repeated under it.
@Composable
private fun SourceErrorBanner(
    failures: List<SourceFailure>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val offline = failures.allOffline()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = if (offline) "No connection" else failures.headline(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                if (!offline) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide" else "Details")
                    }
                }
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (expanded && !offline) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    failures.forEach { failure ->
                        Text(
                            text = failure.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
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
private fun MediaCard(
    title: String,
    coverUrl: String?,
    sourceName: String?,
    saved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
) {
    PosterTile(
        title = title,
        coverUrl = coverUrl,
        onClick = onClick,
        // A grid cell is wider than a rail tile, so the caption gets the larger body size.
        titleStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(8.dp).fillMaxWidth(),
    ) {
        // Quick save/remove without opening the details page; the filled bookmark is the confirmation.
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        ) {
            // 40.dp, not 32: this is a real tap target sitting on top of a poster that is itself
            // clickable, so a miss doesn't do nothing — it opens the title. Material's minimum is
            // 48 dp; 40 is the most that fits over a 120 dp tile without covering the artwork, and
            // it is a good deal harder to miss than 32.
            IconButton(onClick = onToggleSave, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (saved) "Remove from library" else "Save to library",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        sourceName?.let { name ->
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
