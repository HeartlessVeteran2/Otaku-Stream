package com.otakustream.core.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    onAutoSkipChange: (Boolean) -> Unit,
    onSeekDurationChange: (Long) -> Unit,
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
                // "Off" is inside the group, not above it: it is one of the mutually exclusive
                // choices — turning subtitles off is picking an option, not leaving the set — so a
                // screen reader has to count it. Excluded, the group would announce one fewer
                // option than it has and the state the user is actually in would not be in it.
                Column(modifier = Modifier.selectableGroup()) {
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
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Role.Button: these are clickable Rows, so without it a screen reader
                    // announces the text and gives no indication it can be activated.
                    .clickable(onClick = onLoadSubtitleFile, role = Role.Button)
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
                    // Role.Button: these are clickable Rows, so without it a screen reader
                    // announces the text and gives no indication it can be activated.
                    .clickable(onClick = onOpenSubtitleStyle, role = Role.Button)
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

            Text(text = "Playback", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Auto-skip intros & outros", modifier = Modifier.weight(1f))
                Switch(checked = uiState.autoSkipEnabled, onCheckedChange = onAutoSkipChange)
            }
            Text(text = "Double-tap to seek", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                listOf(5L, 10L, 15L, 30L).forEach { seconds ->
                    val millis = seconds * 1000L
                    FilterChip(
                        selected = uiState.seekDurationMs == millis,
                        onClick = { onSeekDurationChange(millis) },
                        label = { Text("${seconds}s") },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, tracks: List<TrackInfo>, onSelect: (TrackInfo) -> Unit) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    // selectableGroup, so the rows are a radio *group* and not three unrelated radio buttons that
    // happen to sit together. It is what makes TalkBack say "2 of 5" as you move through them, and
    // what tells it only one can be chosen. Marking the rows selectable without it says each row is
    // a radio button and nothing about what it belongs to.
    //
    // Around the rows only, not the heading: the heading is not one of the choices.
    Column(modifier = Modifier.selectableGroup()) {
        tracks.forEach { track ->
            TrackRow(label = track.label, isSelected = track.isSelected, onClick = { onSelect(track) })
        }
    }
}

@Composable
private fun TrackRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        // selectable, not clickable — this is one of a set and TalkBack should say so, announcing
        // "selected" for the active track rather than leaving the user to infer it from a radio
        // button they cannot see.
        //
        // The whole row is the target and the RadioButton's own onClick is null. Two tappable nodes
        // for one choice is what the previous version had: TalkBack stopped on the row and again on
        // the button, read the label once and nothing the second time, and both did the same thing.
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(text = label)
    }
}
