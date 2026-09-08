package com.otakustream.feature.sources.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.runtime.getValue
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.CoverImage
import com.otakustream.core.ui.EmptyState

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.ui.LoadingState

// Step between an AniList anime and an actual stream: the user picks the matching result from their
// installed sources, then the app hands off to the normal source detail/playback flow.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListWatchScreen(
    onBack: () -> Unit,
    onBrowseAddons: () -> Unit,
    onOpenSource: (sourceId: Long, mediaUrl: String, title: String, coverUrl: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AniListWatchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // When the bridge resolves a target (an existing mapping, or a fresh pick), hand off.
    LaunchedEffect(uiState.navigateTo) {
        uiState.navigateTo?.let { target ->
            onOpenSource(target.sourceId, target.mediaUrl, target.title, target.coverUrl)
            viewModel.consumeNavigation()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BackTopBar(title = "Find a source", onBack = onBack)
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

            // Weighted, and wrapping the whole `when` rather than one branch of it. Every branch
            // below fills its parent, and as a plain second child of this Column each was measured
            // against the *full* screen height starting under the text field — so the spinner
            // centred itself below the fold and the results grid ran off the bottom. weight(1f)
            // hands them the height that is actually left.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // fillMaxSize on each of these, like every other branch of this `when` — see the
                    // comment on the Box above. EmptyState only fills its width, so without it they
                    // sit at the top of the weighted area rather than centred in it. (The
                    // no-sources branch predates this change and had the same gap.)
                    uiState.hasNoSources -> EmptyState(
                        icon = Icons.Filled.Extension,
                        title = "No sources installed",
                        message = "Install an add-on or extension, then come back to watch this from AniList.",
                        actionLabel = "Browse add-ons",
                        onAction = onBrowseAddons,
                        modifier = Modifier.fillMaxSize(),
                    )
                    uiState.isSearching -> LoadingState()
                    uiState.query.isBlank() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = "Find it in your sources",
                        message = "Type a title above and every installed source is asked for it.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    uiState.groups.isEmpty() -> EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "No matches",
                        message = "None of your sources returned anything for that. Try a different " +
                            "spelling, or the romaji title.",
                        modifier = Modifier.fillMaxSize(),
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
                                        // Explicit poster bounds: CoverImage lays its image out with
                                        // matchParentSize, which contributes nothing to measurement —
                                        // without a size here the row's thumbnail collapses to 0x0 as
                                        // soon as the image loads and replaces the placeholder icon.
                                        CoverImage(
                                            url = item.coverUrl,
                                            contentDescription = item.title,
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .width(40.dp)
                                                .aspectRatio(2f / 3f)
                                                .clip(RoundedCornerShape(4.dp)),
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
}
