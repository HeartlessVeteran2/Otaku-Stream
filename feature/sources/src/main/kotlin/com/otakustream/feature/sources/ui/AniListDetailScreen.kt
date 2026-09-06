package com.otakustream.feature.sources.ui

import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.CoverImage

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.ui.LoadingState
import com.otakustream.core.ui.ProvideTitleAccent
import com.otakustream.core.ui.heroScrim
import com.otakustream.core.ui.onTitleAccent
import com.otakustream.core.ui.titleAccent

// AniList anime detail: the page a discovery rail opens onto. Metadata, your list controls when
// signed in, and a Watch action that hands off to the source search.
//
// (The comment that used to sit here said the list controls and Watch "arrive in later phases".
// Both shipped; it was describing an app that no longer existed.)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListDetailScreen(
    onBack: () -> Unit,
    onOpenAniList: (mediaId: Long, title: String) -> Unit,
    onWatch: (mediaId: Long, title: String) -> Unit,
    onOpenTracking: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AniListDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BackTopBar(title = uiState.media?.displayTitle ?: "Details", onBack = onBack)
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> LoadingState()
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
                    onWatch = onWatch,
                    onOpenTracking = onOpenTracking,
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
    onWatch: (Long, String) -> Unit,
    onOpenTracking: () -> Unit,
    onSetStatus: (String) -> Unit,
    onSetScore: (Double) -> Unit,
    onSetProgress: (Int) -> Unit,
) {
    val media = uiState.media ?: return
    // The cover rather than the banner: a banner is a wide crop of a scene and is often a sky or a
    // wall, where the cover is the image the show was sold with and is the one carrying its colour.
    ProvideTitleAccent(coverUrl = media.coverImageUrl) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (media.bannerImageUrl != null) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    CoverImage(
                        url = media.bannerImageUrl,
                        contentDescription = media.displayTitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The banner used to stop at a hard horizontal edge against the page. The shared
                    // hero scrim dissolves it instead, and carries a trace of this title's colour.
                    Box(modifier = Modifier.fillMaxSize().background(heroScrim()))
                }
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

            // The page's primary action, in the show's own colour. The label is computed from that
            // colour rather than taken from the theme — see onTitleAccent().
            Button(
                onClick = { onWatch(media.id, media.displayTitle) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = titleAccent(),
                    contentColor = onTitleAccent(),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text("Watch")
            }

            // Your-list controls (signed in) or a prompt to connect AniList (signed out).
            if (uiState.isSignedIn) {
                AniListListControls(
                    status = uiState.listStatus,
                    progress = uiState.listProgress,
                    episodeCount = uiState.media?.episodes,
                    score = uiState.listScore,
                    isSaving = uiState.isSaving,
                    saveError = uiState.saveError,
                    onSetStatus = onSetStatus,
                    onSetScore = onSetScore,
                    onSetProgress = onSetProgress,
                )
            } else {
                TextButton(
                    onClick = onOpenTracking,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Sign in to AniList to track your progress and score")
                }
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
                    text = remember(description) { stripHtml(description) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (media.relations.isNotEmpty()) {
                RailHeading("Related")
                LazyRow(contentPadding = RailPadding, horizontalArrangement = RailSpacing) {
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
                LazyRow(contentPadding = RailPadding, horizontalArrangement = RailSpacing) {
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
// Compiled once rather than on every call: stripHtml runs during composition of the synopsis.
private val BR_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private fun stripHtml(raw: String): String = raw
    .replace(BR_TAG, "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&mdash;", "—")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .trim()
