package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.ui.CoverImage
import com.otakustream.core.ui.EmptyState
import com.otakustream.feature.tracking.AiringItem
import com.otakustream.feature.tracking.formatCountdown

// When the next episode of everything on the viewer's AniList list airs, grouped by day.
//
// The view model is supplied by the caller rather than obtained here with a bare hiltViewModel().
// That call scopes to the *destination* it runs in, so this screen would get its own
// AniListHomeViewModel — a second instance re-running discovery and re-fetching the viewer's lists,
// which are not cached. Opening the schedule would cost a full reload of data the Play tab already
// had. The navigation graph hands over the Play destination's instance instead, so this screen
// really does read the state the home rail was built from.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiringScheduleScreen(
    onBack: () -> Unit,
    onAniListClick: (mediaId: Long, title: String) -> Unit,
    viewModel: AniListHomeViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    // One reading shared by every row, advanced on a tick. Per-row clock reads would let the top
    // and bottom of a long list disagree by a minute; a value fixed at first composition would
    // still say "in 1m" a quarter of an hour later.
    val nowMs by rememberTickingNow()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopBar(title = "Airing schedule", onBack = onBack) },
    ) { padding ->
        if (uiState.airingDays.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.CalendarMonth,
                    // Two quite different causes, deliberately described together: not signed in,
                    // and signed in with nothing currently airing. Guessing which one applies would
                    // mean telling half the users something false.
                    title = "Nothing scheduled",
                    message = "Shows you're watching on AniList appear here when they have an " +
                        "episode on the way. Sign in and set something to Watching to fill it.",
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                uiState.airingDays.forEach { day ->
                    item(key = "day-${day.daysFromToday}") {
                        DayHeader(label = day.label, isToday = day.daysFromToday == 0)
                    }
                    day.items.forEach { entry ->
                        item(key = "ep-${entry.media.id}") {
                            AiringRow(
                                item = entry,
                                nowMs = nowMs,
                                onClick = { onAniListClick(entry.media.id, entry.media.displayTitle) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, isToday: Boolean) {
    Column {
        HorizontalDivider()
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            // Today is tinted rather than only bolded: weight alone is easy to miss when scrolling
            // past several headings, and today is the one people are looking for.
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun AiringRow(item: AiringItem, nowMs: Long, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverImage(
            url = item.media.coverImageUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 48.dp, height = 68.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = item.media.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )
            Text(
                text = "Episode ${item.episode} · ${formatCountdown(item.airingAtSeconds, nowMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only when they are actually behind. "Episode 9 airs Friday" reads differently when
            // you are still on episode 3, and that gap is the thing worth flagging.
            val behind = item.episode - 1 - item.progress
            if (behind > 0) {
                Text(
                    text = if (behind == 1) "1 episode waiting" else "$behind episodes waiting",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
