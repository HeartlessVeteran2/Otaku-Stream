package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// The viewer's AniList list editor — status dropdown, episode stepper, score stepper — as a pure,
// state-agnostic composable. Shared so the same control can live on the AniList detail screen and
// on a source's media-details screen (once the title is linked), instead of being duplicated or
// only reachable from one of them. Callers own the state and the writes; this just renders.
@Composable
fun AniListListControls(
    status: String?,
    progress: Int,
    episodeCount: Int?,
    score: Double?,
    isSaving: Boolean,
    saveError: String?,
    onSetStatus: (String) -> Unit,
    onSetScore: (Double) -> Unit,
    onSetProgress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your list", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            // Status dropdown
            var statusExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { statusExpanded = true }, enabled = !isSaving) {
                    Text(aniListStatusLabel(status))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                    ANILIST_STATUSES.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(aniListStatusLabel(option)) },
                            onClick = {
                                statusExpanded = false
                                onSetStatus(option)
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
                    onClick = { onSetProgress(progress - 1) },
                    enabled = !isSaving && progress > 0,
                ) { Icon(Icons.Filled.Remove, contentDescription = "One fewer episode") }
                Text(
                    text = episodeCount?.let { "$progress / $it" } ?: "$progress",
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(
                    onClick = { onSetProgress(progress + 1) },
                    enabled = !isSaving,
                ) { Icon(Icons.Filled.Add, contentDescription = "One more episode") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Score stepper (AniList POINT_10 scale)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Score", modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onSetScore((score ?: 0.0) - 1.0) },
                    enabled = !isSaving && (score ?: 0.0) > 0.0,
                ) { Icon(Icons.Filled.Remove, contentDescription = "Lower score") }
                Text(
                    text = score?.let { "${it.toInt()} / 10" } ?: "–",
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(
                    onClick = { onSetScore((score ?: 0.0) + 1.0) },
                    enabled = !isSaving && (score ?: 0.0) < 10.0,
                ) { Icon(Icons.Filled.Add, contentDescription = "Raise score") }
            }

            if (saveError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
