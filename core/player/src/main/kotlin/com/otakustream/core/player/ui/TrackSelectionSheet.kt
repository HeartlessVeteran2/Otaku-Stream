package com.otakustream.core.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otakustream.core.player.PlayerUiState
import com.otakustream.core.player.TrackInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionSheet(
    uiState: PlayerUiState,
    onSelectAudio: (TrackInfo) -> Unit,
    onSelectSubtitle: (TrackInfo) -> Unit,
    onSelectQuality: (TrackInfo) -> Unit,
    onSubtitlesEnabledChange: (Boolean) -> Unit,
    onLoadSubtitleFile: () -> Unit,
    onOpenSubtitleStyle: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (uiState.audioTracks.isNotEmpty()) {
                TrackSection(title = "Audio", tracks = uiState.audioTracks, onSelect = onSelectAudio)
            }
            // Always visible — even with no embedded/addon tracks, the user can load a file.
            Text(text = "Subtitles", style = MaterialTheme.typography.titleMedium)
            if (uiState.subtitleTracks.isNotEmpty()) {
                TrackRow(
                    label = "Off",
                    isSelected = !uiState.subtitlesEnabled,
                    onClick = { onSubtitlesEnabledChange(false) },
                )
                uiState.subtitleTracks.forEach { track ->
                    TrackRow(
                        label = track.label,
                        isSelected = uiState.subtitlesEnabled && track.isSelected,
                        onClick = {
                            onSubtitlesEnabledChange(true)
                            onSelectSubtitle(track)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLoadSubtitleFile)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Load subtitle file…")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSubtitleStyle)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.FormatColorText, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Subtitle style")
            }
            if (uiState.videoQualityTracks.isNotEmpty()) {
                TrackSection(title = "Quality", tracks = uiState.videoQualityTracks, onSelect = onSelectQuality)
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, tracks: List<TrackInfo>, onSelect: (TrackInfo) -> Unit) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    tracks.forEach { track ->
        TrackRow(label = track.label, isSelected = track.isSelected, onClick = { onSelect(track) })
    }
}

@Composable
private fun TrackRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Text(text = label)
    }
}
