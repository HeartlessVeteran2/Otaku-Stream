package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// AniList anime detail. Phase 3 renders read-only metadata; the list controls and a working Watch
// action arrive in later phases. Kept intentionally simple so it's a real, tappable payoff for the
// discovery rails without pulling Phase 5/6 scope forward.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListDetailScreen(
    onBack: () -> Unit,
    onOpenAniList: (mediaId: Long, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AniListDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiState.media?.displayTitle ?: "Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text("Retry") }
                    }
                }
                uiState.media != null -> DetailContent(
                    uiState = uiState,
                    onOpenAniList = onOpenAniList,
                    onSetStatus = viewModel::setStatus,
                    onSetScore = viewModel::setScore,
                    onSetProgress = viewModel::setProgress,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    uiState: AniListDetailUiState,
    onOpenAniList: (Long, String) -> Unit,
    onSetStatus: (String) -> Unit,
    onSetScore: (Double) -> Unit,
    onSetProgress: (Int) -> Unit,
) {
    val media = uiState.media ?: return
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (media.bannerImageUrl != null) {
            CoverImage(
                url = media.bannerImageUrl,
                contentDescription = media.displayTitle,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            CoverImage(
                url = media.coverImageUrl,
                contentDescription = media.displayTitle,
                modifier = Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(media.displayTitle, style = MaterialTheme.typography.titleLarge)
                media.romajiTitle?.takeIf { it != media.displayTitle }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = listOfNotNull(
                        media.format,
                        media.episodes?.let { "$it eps" },
                        media.seasonYear?.let { year -> media.season?.let { "${it.lowercase().replaceFirstChar(Char::uppercase)} $year" } ?: "$year" },
                        media.averageScore?.let { "★ $it%" },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                )
                media.nextAiringEpisode?.let { ep ->
                    Text(
                        "Next: episode $ep",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        // Watch is wired up in Phase 6 (cross-source match + play). Shown disabled so the intended
        // flow is visible without promising behavior that isn't built yet.
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("Watch (coming soon)")
        }

        // Your-list controls (signed in) or a prompt to connect AniList (signed out).
        if (uiState.isSignedIn) {
            ListControls(
                uiState = uiState,
                onSetStatus = onSetStatus,
                onSetScore = onSetScore,
                onSetProgress = onSetProgress,
            )
        } else {
            Text(
                text = "Connect AniList in Settings to track your progress and score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (media.genres.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(media.genres, key = { it }) { genre ->
                    AssistChip(onClick = {}, label = { Text(genre) })
                }
            }
        }

        media.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = stripHtml(description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (media.relations.isNotEmpty()) {
            RailHeading("Related")
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(media.relations, key = { "rel-${it.media.id}" }) { relation ->
                    AniListPosterTile(
                        title = relation.media.displayTitle,
                        coverUrl = relation.media.coverImageUrl,
                        subtitle = relation.relationType?.replace('_', ' ')?.lowercase()
                            ?.replaceFirstChar(Char::uppercase),
                        onClick = { onOpenAniList(relation.media.id, relation.media.displayTitle) },
                    )
                }
            }
        }

        if (media.recommendations.isNotEmpty()) {
            RailHeading("Recommended")
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(media.recommendations, key = { "rec-${it.id}" }) { rec ->
                    AniListPosterTile(
                        title = rec.displayTitle,
                        coverUrl = rec.coverImageUrl,
                        subtitle = null,
                        onClick = { onOpenAniList(rec.id, rec.displayTitle) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ListControls(
    uiState: AniListDetailUiState,
    onSetStatus: (String) -> Unit,
    onSetScore: (Double) -> Unit,
    onSetProgress: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your list", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            // Status dropdown
            var statusExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { statusExpanded = true }, enabled = !uiState.isSaving) {
                    Text(aniListStatusLabel(uiState.listStatus))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                    ANILIST_STATUSES.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(aniListStatusLabel(status)) },
                            onClick = {
                                statusExpanded = false
                                onSetStatus(status)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress stepper
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Episode", modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onSetProgress(uiState.listProgress - 1) },
                    enabled = !uiState.isSaving && uiState.listProgress > 0,
                ) { Icon(Icons.Filled.Remove, contentDescription = "One fewer episode") }
                Text(
                    text = uiState.media?.episodes?.let { "${uiState.listProgress} / $it" }
                        ?: "${uiState.listProgress}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(
                    onClick = { onSetProgress(uiState.listProgress + 1) },
                    enabled = !uiState.isSaving,
                ) { Icon(Icons.Filled.Add, contentDescription = "One more episode") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Score stepper (AniList POINT_10 scale)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Score", modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onSetScore((uiState.listScore ?: 0.0) - 1.0) },
                    enabled = !uiState.isSaving && (uiState.listScore ?: 0.0) > 0.0,
                ) { Icon(Icons.Filled.Remove, contentDescription = "Lower score") }
                Text(
                    text = uiState.listScore?.let { "${it.toInt()} / 10" } ?: "–",
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(
                    onClick = { onSetScore((uiState.listScore ?: 0.0) + 1.0) },
                    enabled = !uiState.isSaving && (uiState.listScore ?: 0.0) < 10.0,
                ) { Icon(Icons.Filled.Add, contentDescription = "Raise score") }
            }

            if (uiState.saveError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(uiState.saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RailHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

// AniList descriptions come back with a few inline HTML tags even with asHtml:false (br, i, b).
// A light strip keeps the synopsis readable without pulling in an HTML renderer.
private fun stripHtml(raw: String): String = raw
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&mdash;", "—")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .trim()
