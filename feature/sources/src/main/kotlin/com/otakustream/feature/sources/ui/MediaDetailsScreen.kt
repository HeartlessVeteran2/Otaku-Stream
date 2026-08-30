package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.otakustream.core.ui.CoverImage
import com.otakustream.core.ui.EmptyState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING
import com.otakustream.core.database.tracking.toTrackerSeason
import com.otakustream.core.sources.api.Video
import com.otakustream.feature.tracking.LinkAniListDialog

// Which link the AniList dialog is being opened to create. A nullable holder rather than a bare
// `Int?`, because "closed" and "open, linking the whole series" are both null-ish and must stay
// distinguishable.
private data class LinkTarget(val season: Int?)

@Composable
fun MediaDetailsScreen(
    sourceId: Long,
    mediaUrl: String,
    mediaTitle: String,
    onPlayVideo: (videoUrl: String) -> Unit,
    onOpenTracking: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MediaDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val inLibrary by viewModel.inLibrary.collectAsState()
    val libraryStatus by viewModel.libraryStatus.collectAsState()
    val watchedEpisodeUrls by viewModel.watchedEpisodeUrls.collectAsState()
    val downloadedEpisodeUrls by viewModel.downloadedEpisodeUrls.collectAsState()
    val trackerLink by viewModel.trackerLink.collectAsState()
    val selectedSeason by viewModel.selectedSeason.collectAsState()
    val hasTrackerToken by viewModel.hasTrackerToken.collectAsState()
    val aniListEntry by viewModel.aniListEntry.collectAsState()
    val autoPlayEnabled by viewModel.autoPlayEnabled.collectAsState()
    // null = dialog closed. Open, it carries which link to create — see LinkTarget.
    var linkTarget by remember { mutableStateOf<LinkTarget?>(null) }

    LaunchedEffect(sourceId, mediaUrl) {
        viewModel.load(sourceId, mediaUrl, mediaTitle)
    }

    LaunchedEffect(uiState.resolvedVideoUrl) {
        uiState.resolvedVideoUrl?.let {
            onPlayVideo(it)
            viewModel.consumeResolvedVideoUrl()
        }
    }

    // Hoisted above the list because LazyColumn's builder is a LazyListScope, not a composable
    // scope: remember and LaunchedEffect cannot live inside it, and these decide which items it
    // emits.
    val seasons = remember(uiState.episodes) { uiState.episodes.mapNotNull { it.season }.distinct().sorted() }
    // Selection lives in the ViewModel: it decides which episodes are listed *and* which AniList
    // entry the link row targets, since AniList models each season separately. Keyed on mediaUrl
    // too: two titles can have the same season numbers, so `seasons` alone wouldn't change on
    // navigation and the selection the ViewModel just cleared would never be re-seeded, leaving the
    // episode list filtered to a season that matches nothing.
    LaunchedEffect(mediaUrl, seasons) { viewModel.selectSeason(seasons.firstOrNull()) }
    val visibleEpisodes = remember(uiState.episodes, selectedSeason) {
        if (seasons.isEmpty()) uiState.episodes else uiState.episodes.filter { it.season == selectedSeason }
    }
    val watchedCount = remember(visibleEpisodes, watchedEpisodeUrls) {
        visibleEpisodes.count { it.url in watchedEpisodeUrls }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopBar(title = mediaTitle, onBack = onBack) },
    ) { padding ->
        // Held as a lambda rather than pulled out into a function with a parameter list.
        //
        // It reads a dozen pieces of hoisted state and calls back into the view model; passing
        // all of that explicitly would be a long signature that has to be kept in step, and the
        // only reason to do it would be reuse this file does not need. Capturing lexically means
        // the phone and tablet layouts below are provably rendering the same header.
        val header: @Composable () -> Unit = {
            Column {
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
                    // When the selected season has no link of its own, this row is showing the
                    // whole-series link as a fallback. Say so, and offer to link this season
                    // specifically — otherwise every season would silently report the same AniList entry
                    // and push progress at it. Derived from the resolved link itself rather than from a
                    // second "which seasons are linked" flow: the two are separate Room queries that
                    // emit independently, so between emissions they'd disagree and this row would
                    // describe a link it isn't showing.
                    val isFallback = link.season != selectedSeason.toTrackerSeason()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isFallback) {
                                "AniList (whole series): ${link.trackerTitle}"
                            } else if (link.season > 0) {
                                "AniList (season ${link.season}): ${link.trackerTitle}"
                            } else {
                                "AniList: ${link.trackerTitle}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = viewModel::unlinkTracker) { Text("Unlink") }
                    }
                    if (isFallback && hasTrackerToken) {
                        TextButton(onClick = { linkTarget = LinkTarget(season = selectedSeason) }) {
                            Text("Link season $selectedSeason separately")
                        }
                    }
                    // The same status/score/progress editor as the AniList detail screen, so a linked
                    // title is managed here without leaving for the AniList tab.
                    AniListListControls(
                        status = aniListEntry.status,
                        progress = aniListEntry.progress,
                        // Counted against whatever the resolved link actually covers. AniList models
                        // each season as its own media entry, so a season link's progress is
                        // season-relative and the series total would render "3 / 87" for episode 3
                        // of a 12-episode season. The whole-series fallback is the opposite case and
                        // genuinely does want the total. With no season data the two are the same
                        // list, so this changes nothing for single-season titles.
                        episodeCount = (if (isFallback) uiState.episodes.size else visibleEpisodes.size)
                            .takeIf { it > 0 },
                        score = aniListEntry.score,
                        isSaving = aniListEntry.isSaving,
                        saveError = aniListEntry.saveError,
                        onSetStatus = viewModel::setAniListStatus,
                        onSetScore = viewModel::setAniListScore,
                        onSetProgress = viewModel::setAniListProgress,
                    )
                } ?: if (hasTrackerToken) {
                    // The first link a title gets is deliberately the whole-series one (season = null),
                    // even on a multi-season show with a season selected. Everything that looks up a
                    // link without a season in hand — library status changes, sources with no season
                    // data — resolves through that row, so creating only a season-N row here would
                    // leave the title reading as unlinked everywhere else. Per-season links are then
                    // added on top via "Link season N separately".
                    TextButton(onClick = { linkTarget = LinkTarget(season = null) }) { Text("Link to AniList") }
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

                if (seasons.isNotEmpty()) {
                    LazyRow(modifier = Modifier.padding(top = 16.dp)) {
                        items(seasons, key = { it }) { season ->
                            FilterChip(
                                selected = season == selectedSeason,
                                onClick = { viewModel.selectSeason(season) },
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

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // A LazyListScope builder, so the same rows are emitted into whichever list is used.
        val episodeItems: LazyListScope.() -> Unit = {
        items(visibleEpisodes, key = { it.url }) { episode ->
            val watched = episode.url in watchedEpisodeUrls
            val downloaded = episode.url in downloadedEpisodeUrls
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
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (resolving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else if (watched) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Watched",
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // Its own tap target beside the row rather than a long-press or a menu:
                        // saving an episode for offline is a deliberate act, and a hidden gesture
                        // would mean the feature exists but nobody finds it.
                        IconButton(
                            onClick = {
                                if (downloaded) {
                                    viewModel.cancelDownload(episode)
                                } else {
                                    viewModel.downloadEpisode(sourceId, episode)
                                }
                            },
                            enabled = rowEnabled,
                        ) {
                            Icon(
                                imageVector = if (downloaded) {
                                    Icons.Filled.DownloadDone
                                } else {
                                    Icons.Filled.Download
                                },
                                contentDescription = if (downloaded) {
                                    "Remove download"
                                } else {
                                    "Download episode"
                                },
                                tint = if (downloaded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.clickable(enabled = rowEnabled) {
                    viewModel.playEpisode(sourceId, episode)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        }

        // Two panes once there is width for them, one column otherwise.
        //
        // BoxWithConstraints rather than WindowSizeClass: the decision is about the space this
        // screen actually has, which is not the same as the window's size when the app is in
        // split-screen or a freeform window. It also needs no extra dependency.
        //
        // On a tablet the single column wastes the width twice over — a 280 dp hero stretched to
        // 1200 dp, and an episode list pushed below the fold that the user came here to use.
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= TWO_PANE_MIN_WIDTH) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // The header scrolls independently: it is taller than a tablet screen once a
                    // long synopsis and the AniList controls are in it, so a fixed pane would clip.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        header()
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                        content = episodeItems,
                    )
                }
            } else {
                // One scrolling list for the whole screen, header included.
                //
                // This used to be a Column holding a 280 dp hero, a status row, the AniList
                // controls, an unbounded synopsis, the cast line and the season chips, with the
                // episode LazyColumn last and no weight. A Column hands each child the height it
                // asks for in order and gives the last one whatever is left — which in landscape
                // is nothing. The episode list, the entire point of the screen, measured to
                // roughly zero and could not be reached or scrolled to. Folding the header into
                // the list means it scrolls away instead of competing for height.
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item { header() }
                    episodeItems()
                }
            }
        }
    }

    linkTarget?.let { target ->
        LinkAniListDialog(
            mediaUrl = mediaUrl,
            sourceId = sourceId,
            // Seed the search with the season, since AniList lists them as separate titles
            // ("Show Season 2"), so the right entry is usually the first result. The whole-series
            // link searches the plain title.
            defaultQuery = target.season?.takeIf { it > 1 }?.let { "$mediaTitle Season $it" } ?: mediaTitle,
            onDismiss = { linkTarget = null },
            season = target.season,
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
            // ListItem rows, not radio buttons. The radios were hardcoded `selected = false`, and
            // there was no way to make them ever look selected: selectVideo clears
            // pendingVideoChoices in the same state update, so the sheet is gone before a selection
            // could render. A radio group promises a persistent choice this sheet does not have —
            // each row is a one-shot action, so it looks like one now.
            itemsIndexed(choices) { index, video ->
                ListItem(
                    headlineContent = { Text(text = prettyQuality(video.quality, index)) },
                    // Role.Button: the radios carried a control role for screen readers, and plain
                    // clickable text does not. Button, not RadioButton — these are one-shot actions,
                    // not a persistent choice.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onSelect(video) },
                )
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

// Below this, a second pane would be too narrow for either half to be usable — the hero would be
// letterboxed and episode titles would wrap to three lines. Chosen against the width the screen
// actually has rather than a device class, so a tablet in split-screen correctly gets one column.
private val TWO_PANE_MIN_WIDTH = 720.dp
