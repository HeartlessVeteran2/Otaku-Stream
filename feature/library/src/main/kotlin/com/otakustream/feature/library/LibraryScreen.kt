package com.otakustream.feature.library


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.otakustream.core.database.library.DIRECT_PLAY_SOURCE_ID
import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.Video
import com.otakustream.core.ui.ConfirmDialog
import com.otakustream.core.ui.CoverImage
import com.otakustream.core.ui.EmptyState
import com.otakustream.feature.library.local.LocalVideosViewModel
import com.otakustream.feature.library.local.findSidecarSubtitles
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onMediaClick: (sourceId: Long, mediaUrl: String, title: String, coverUrl: String?) -> Unit,
    onPlayDirect: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // rememberSaveable: which tab you were on survives a rotation and a process-death restore.
    // Plain remember dropped it, so coming back to the app landed on Watchlist however deep into
    // History or On device you had been.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Direct plays (local files, pasted links) have no details page — route them straight back
    // into the player; catalog entries open their details as before.
    val onEntryClick: (Long, String, String, String?) -> Unit = { sourceId, mediaUrl, title, coverUrl ->
        if (sourceId == DIRECT_PLAY_SOURCE_ID) {
            onPlayDirect(mediaUrl)
        } else {
            onMediaClick(sourceId, mediaUrl, title, coverUrl)
        }
    }

    // A real TopAppBar rather than a Text styled to look like one. Two of the four tabs did that —
    // with different padding from each other — so the app's title bar changed height and alignment
    // depending on which tab you were on. It is also where a search field and a sort menu can go.
    // Search and sort apply to the two tabs that are lists of titles. Downloads is short by nature
    // and On device is a filesystem scan with its own controls, so putting a filter above them
    // would be chrome that does nothing.
    val showsFilters = selectedTab == 0 || selectedTab == 1
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(WatchlistSort.RecentlyAdded) }
    // Cleared when the filters go away, so switching to Downloads and back does not leave a hidden
    // query silently filtering the list.
    LaunchedEffect(showsFilters) { if (!showsFilters) query = "" }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Library") })
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Watchlist") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("History") })
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    // A download running or failed was visible nowhere outside this tab, so the
                    // only way to learn one had failed was to come looking. The badge counts both:
                    // in flight and needing attention are the two states worth leaving the tab for.
                    val active = uiState.downloads.count { row ->
                        // A null progress is the "Not started" the row itself draws in the error
                        // colour: the metadata was written and the download never began. Leaving it
                        // out of the count meant the one state you cannot see from anywhere else
                        // was also the one the badge stayed silent about.
                        row.isPending ||
                            row.progress == null ||
                            row.progress.state == DownloadProgress.State.FAILED
                    }
                    if (active > 0) {
                        BadgedBox(badge = { Badge { Text(active.toString()) } }) { Text("Downloads") }
                    } else {
                        Text("Downloads")
                    }
                },
            )
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("On device") })
        }

        if (showsFilters) {
            LibraryFilterRow(
                query = query,
                onQueryChange = { query = it },
                sort = sort,
                onSortChange = { sort = it },
                // Sorting applies to the watchlist only. History is a log — it is in the order
                // things happened, and reordering it by title would make it something else.
                showSort = selectedTab == 0,
            )
        }

        when (selectedTab) {
            0 -> WatchlistTab(uiState, viewModel, onEntryClick, query, sort)
            1 -> HistoryTab(uiState, viewModel, onEntryClick, query)
            2 -> DownloadsTab(uiState, viewModel, onPlayDirect)
            else -> OnDeviceTab(onPlayDirect)
        }
    }
}

// How the watchlist is ordered inside each status section.
//
// Session-scoped rather than persisted: a sort is a way of looking at the list right now, and the
// default — what you saved most recently — is the one that is right most of the time. Persisting it
// would mostly mean coming back to an order you picked once for a reason that has passed.
private enum class WatchlistSort(val label: String) {
    RecentlyAdded("Recently added"),
    Title("Title A-Z"),
}

// Watchlist status buckets, in the order they're shown.
private val LIBRARY_STATUS_SECTIONS = listOf(
    LIBRARY_STATUS_WATCHING to "Watching",
    LIBRARY_STATUS_PLANNED to "Plan to watch",
    LIBRARY_STATUS_COMPLETED to "Completed",
)

// The search field and sort menu, above whichever list they apply to.
@Composable
private fun LibraryFilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: WatchlistSort,
    onSortChange: (WatchlistSort) -> Unit,
    showSort: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                // Only when there is something to clear — an always-present X on an empty field is
                // a control that does nothing.
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (showSort) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort: ${sort.label}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    WatchlistSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onSortChange(option)
                                expanded = false
                            },
                            // The current choice is readable without opening the menu twice.
                            trailingIcon = {
                                if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

// Case-insensitive substring on the title. Deliberately not fuzzy: a library is a list somebody
// built themselves, so they know roughly what the thing is called, and a match that is hard to
// predict is worse than one that is strict.
private fun matchesQuery(title: String, query: String): Boolean =
    query.isBlank() || title.contains(query.trim(), ignoreCase = true)

@Composable
private fun WatchlistTab(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onMediaClick: (Long, String, String, String?) -> Unit,
    query: String,
    sort: WatchlistSort,
) {
    // One section per non-empty status bucket. Unmigrated rows (status not one of the known values)
    // fall back into "Plan to watch" so nothing is ever hidden. Remembered and hoisted above the
    // LazyColumn: the content lambda re-runs whenever the list is re-laid out, so grouping inside it
    // rebuilt the map and all its sublists each time — and the filter and sort belong in the same
    // remember for the same reason.
    val byStatus = remember(uiState.watchlist, query, sort) {
        uiState.watchlist
            .filter { matchesQuery(it.title, query) }
            .let { entries ->
                when (sort) {
                    // observeAll already orders by addedAtEpochMs DESC, so this is the list as it
                    // arrives — named rather than left implicit, because the menu has to be able
                    // to say what the default is.
                    WatchlistSort.RecentlyAdded -> entries
                    WatchlistSort.Title -> entries.sortedBy { it.title.lowercase() }
                }
            }
            .groupBy { entry ->
                if (LIBRARY_STATUS_SECTIONS.any { it.first == entry.status }) entry.status else LIBRARY_STATUS_PLANNED
            }
    }
    val matches = remember(byStatus) { byStatus.values.sumOf { it.size } }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Hidden while searching. It is a rail of recent activity, not part of the watchlist
        // being filtered, so leaving it up would show titles that do not match the query directly
        // above a list that excluded them.
        if (uiState.continueWatching.isNotEmpty() && query.isBlank()) {
            item {
                Text(
                    text = "Continue watching",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(uiState.continueWatching, key = { "cw-${it.id}" }) { entry ->
                HistoryRow(entry) { onMediaClick(entry.sourceId, entry.mediaUrl, entry.mediaTitle, entry.coverUrl) }
            }
        }

        // Two different nothings, and telling them apart matters: one means go and save
        // something, the other means your search matched none of what you already have.
        if (uiState.watchlist.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.BookmarkBorder,
                    title = "Nothing saved yet",
                    message = "Tap the bookmark on any title — or on a poster in Browse — and it lands here.",
                )
            }
        } else if (matches == 0) {
            item {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "No matches",
                    message = "Nothing in your watchlist matches \"$query\".",
                )
            }
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
                        onClick = { onMediaClick(entry.sourceId, entry.mediaUrl, entry.title, entry.coverUrl) },
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
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun HistoryTab(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onMediaClick: (Long, String, String, String?) -> Unit,
    query: String,
) {
    val history = remember(uiState.history, query) {
        uiState.history.filter { matchesQuery(it.mediaTitle, query) }
    }
    // Clearing history is not undoable and the button sits directly above the list it destroys, so
    // it asks first. It is also the only destructive action on this screen with no other route back
    // — the rows themselves came from playback, and nothing rebuilds them.
    var confirmingClear by remember { mutableStateOf(false) }
    if (confirmingClear) {
        // ConfirmDialog, not a hand-rolled AlertDialog. The shared one tints its confirm button
        // with colorScheme.error, which this dialog was missing — so the single irreversible action
        // on this screen looked exactly like Cancel, in the one place the difference matters. It is
        // also where the app's rule about destructive actions is written down, and every other
        // "are you sure" in the app already goes through it.
        ConfirmDialog(
            title = "Clear watch history?",
            body = "This removes every entry, including your Continue Watching row. It can't be undone.",
            confirmLabel = "Clear",
            onConfirm = viewModel::clearHistory,
            onDismiss = { confirmingClear = false },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Keyed on the unfiltered history: a search that matches nothing has not made clearing
        // meaningless, and hiding the button would suggest there is nothing there to clear.
        if (uiState.history.isNotEmpty()) {
            item {
                TextButton(onClick = { confirmingClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Clear history")
                }
            }
        }

        // The same two nothings the Watchlist tab distinguishes, and this tab was missing the
        // second: a search matching none of your history rendered the Clear button over a blank
        // list, which reads as a screen that failed to load rather than as a search with no hits.
        if (uiState.history.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = "No watch history yet",
                    message = "Anything you play — a file, a link, or an episode — shows up here so you can pick it back up.",
                )
            }
        } else if (history.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "No matches",
                    message = "Nothing in your history matches \"$query\".",
                )
            }
        }
        items(history, key = { it.id }) { entry ->
            HistoryRow(
                entry = entry,
                onRemove = { viewModel.removeHistoryEntry(entry.id) },
            ) { onMediaClick(entry.sourceId, entry.mediaUrl, entry.mediaTitle, entry.coverUrl) }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: WatchHistoryEntry,
    // Null on the Continue-watching rail, which reuses this row: removing a history entry from
    // there would look like removing the show from the rail and do something else.
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.mediaTitle) },
        trailingContent = onRemove?.let {
            {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove ${entry.mediaTitle} from history",
                    )
                }
            }
        },
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
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
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
    // For the sidecar-subtitle scan below, which has to leave the main thread and then come back to
    // navigate. Still alive at that point: this composable only leaves composition once the
    // navigation it triggers actually happens.
    val scope = rememberCoroutineScope()
    // One launch at a time. The scan is now asynchronous, so tapping two rows quickly used to start
    // two of them — and whichever finished second would overwrite PendingPlayback and navigate on
    // top of the first, so the player could open the file the user tapped *first* carrying the
    // subtitles of the one they tapped second. The newest tap wins, which is what a tap means.
    var launchJob by remember { mutableStateOf<Job?>(null) }

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
                            launchJob?.cancel()
                            launchJob = scope.launch {
                                // VLC-style sidecar subtitles: hand any same-basename
                                // .srt/.ass/.ssa/.vtt next to the file to the player.
                                //
                                // Off the main thread. This lists the directory the video sits in,
                                // and on a shared-storage folder holding hundreds of files that is
                                // real disk work — it used to run inside the click handler, so the
                                // tap that should open the player instead blocked on the filesystem
                                // and dropped frames on the way out of this screen.
                                val sidecars = withContext(Dispatchers.IO) {
                                    findSidecarSubtitles(video.dataPath)
                                }
                                if (sidecars.isNotEmpty()) {
                                    // historyHandled = false keeps the player recording this as a
                                    // direct play as usual.
                                    PendingPlayback.stash(
                                        Video(url = url, quality = "", subtitleTracks = sidecars),
                                        historyHandled = false,
                                        // The user picked this file from their own device, and the
                                        // sidecar subtitles were found next to it by the app — so
                                        // the local schemes on-device playback needs are legitimate
                                        // here.
                                        provenance = PendingPlayback.Provenance.USER,
                                    )
                                }
                                // After the stash, always: the player reads PendingPlayback as it
                                // starts, so navigating first would race the subtitles into place.
                                onPlayDirect(url)
                            }
                        },
                    )
                }
            }
        }
    }
}


// Saved episodes, and how far along the unfinished ones are.
//
// Tapping a finished row plays it by its stream url — the same url it was downloaded under, which
// is what lets the player serve it from disk with no offline-specific path. A row that is still
// downloading is not playable yet, so it offers pause/resume instead of pretending otherwise.
@Composable
private fun DownloadsTab(
    uiState: LibraryUiState,
    viewModel: LibraryViewModel,
    onPlayDirect: (String) -> Unit,
) {
    if (uiState.downloads.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Download,
            title = "No downloads",
            message = "Tap the download icon beside an episode to save it for watching offline.",
        )
        return
    }
    // Deleting a download deletes bytes, so it asks. Everything else in this file that removes
    // something restores it from the database instead, and offers Undo.
    var pendingDelete by remember { mutableStateOf<DownloadRow?>(null) }
    pendingDelete?.let { row ->
        ConfirmDialog(
            title = "Delete download?",
            body = "\"${row.entry.mediaTitle}\" will be removed from this device. Watching it again " +
                "means downloading it again.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.removeDownload(row) },
            onDismiss = { pendingDelete = null },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // A removal that could not be confirmed, shown above the list it is about — the row is
        // still there and its Delete button still works, which is the whole reason to keep this on
        // this screen rather than in a snackbar the user would read on some other tab.
        uiState.downloadError?.let { message ->
            item(key = "download-error") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::consumeDownloadError) { Text("Dismiss") }
                    }
                }
            }
        }
        items(uiState.downloads, key = { it.entry.videoUrl }) { row ->
            val progress = row.progress
            val finished = progress?.isFinished == true
            ListItem(
                leadingContent = {
                    CoverImage(
                        url = row.entry.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 44.dp, height = 62.dp),
                    )
                },
                headlineContent = { Text(row.entry.mediaTitle, maxLines = 1) },
                supportingContent = {
                    Column {
                        Text(
                            text = row.entry.episodeName ?: "Episode",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        when {
                            finished -> Text(
                                text = "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            progress == null -> Text(
                                // The metadata row is written before the download starts, so this
                                // is what a start that never happened looks like. Saying so beats a
                                // progress bar stuck at zero with no explanation.
                                text = "Not started",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            progress.state == DownloadProgress.State.FAILED -> Text(
                                text = "Failed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> {
                                LinearProgressIndicator(
                                    progress = { progress.percentDownloaded / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                                Text(
                                    text = when (progress.state) {
                                        DownloadProgress.State.PAUSED -> "Paused"
                                        DownloadProgress.State.QUEUED -> "Queued"
                                        DownloadProgress.State.REMOVING -> "Removing"
                                        else -> "${progress.percentDownloaded.toInt()}%"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.isPending) {
                            IconButton(
                                onClick = {
                                    if (progress?.state == DownloadProgress.State.PAUSED) {
                                        viewModel.resumeDownload(row)
                                    } else {
                                        viewModel.pauseDownload(row)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (progress?.state == DownloadProgress.State.PAUSED) {
                                        Icons.Filled.PlayArrow
                                    } else {
                                        Icons.Filled.Pause
                                    },
                                    contentDescription = if (progress?.state == DownloadProgress.State.PAUSED) {
                                        "Resume download"
                                    } else {
                                        "Pause download"
                                    },
                                )
                            }
                        }
                        // A failed row now has a way forward as well as a way out. It was the
                        // only state in the app whose sole affordance was to throw the thing away.
                        //
                        // A null progress counts as failed here. It means the row is in the
                        // database but Media3 has no download for it — the enqueue never took, or
                        // its state was lost — so it will never make progress on its own and Retry
                        // is exactly what it needs. The tab's badge already counts these rows as
                        // wanting attention, so hiding Retry left the badge pointing at a row whose
                        // only button was Delete.
                        if (progress == null || progress.state == DownloadProgress.State.FAILED) {
                            IconButton(onClick = { viewModel.retryDownload(row) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Retry download")
                            }
                        }
                        IconButton(onClick = { pendingDelete = row }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                        }
                    }
                },
                // Only a finished download is playable. Tapping an unfinished one would start a
                // stream of the same url, which works but quietly uses the data the download was
                // meant to save.
                modifier = if (finished) {
                    Modifier.clickable { onPlayDirect(row.entry.videoUrl) }
                } else {
                    Modifier
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
