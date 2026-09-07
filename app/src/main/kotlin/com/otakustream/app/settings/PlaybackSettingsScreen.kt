package com.otakustream.app.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.player.ui.SubtitleStyleControls
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.SectionHeader

// Everything about how playback behaves, in the place someone looks for it.
//
// None of these settings were reachable outside a playing video. Auto-skip and the double-tap seek
// step lived in the player's track sheet, subtitle appearance in a bottom sheet off that same menu,
// and default speed in the speed menu — so configuring the player meant starting an episode,
// adjusting things over the top of it, and hoping. Someone checking what the app could do before
// watching anything found a Settings screen that said nothing about playback at all.
//
// The values are the same ones the player reads (PlayerSettingsPrefs, SubtitleStylePrefs) and the
// subtitle controls are literally the same composable the player's sheet hosts, so this screen
// cannot drift from what playback actually does.
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        BackTopBar(title = "Playback", onBack = onBack)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Skipping")
            ListItem(
                headlineContent = { Text("Skip intros and outros") },
                supportingContent = {
                    Text(
                        "Jumps openings and endings automatically, using AniSkip timestamps. Only " +
                            "applies to episodes AniSkip has data for.",
                    )
                },
                trailingContent = {
                    Switch(checked = uiState.autoSkipEnabled, onCheckedChange = viewModel::setAutoSkip)
                },
            )

            SectionHeader("Controls")
            ChipSetting(
                title = "Double-tap seek",
                description = "How far a double-tap on either side of the video jumps.",
                options = SEEK_STEPS,
                selected = uiState.seekDurationMs,
                label = { "${it / 1000}s" },
                onSelect = viewModel::setSeekDuration,
            )
            ChipSetting(
                title = "Default speed",
                description = "Applied at the start of every video. Changing speed mid-episode " +
                    "does not change this.",
                options = SPEEDS,
                selected = uiState.defaultSpeed,
                label = { speedLabel(it) },
                onSelect = viewModel::setDefaultSpeed,
            )

            SectionHeader("Subtitles")
            // The player's own controls, hosted here rather than copied — see SubtitleStyleControls.
            SubtitleStyleControls(
                style = uiState.subtitleStyle,
                onStyleChange = viewModel::setSubtitleStyle,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            )
        }
    }
}

// A labelled row of mutually exclusive choices. Chips rather than a dropdown because there are only
// a handful of each and the current value should be readable without opening anything.
@Composable
private fun <T> ChipSetting(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall)
                // Scrollable, for the same reason the theme chips are: at a large font scale a
                // fixed Row clips the last chip, and a clipped chip is an option nobody can pick.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        FilterChip(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            label = { Text(label(option)) },
                        )
                    }
                }
            }
        },
    )
}

private val SEEK_STEPS = listOf(5_000L, 10_000L, 15_000L, 30_000L)
private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

// "1x" rather than "1.0x", and "1.25x" rather than "1.25000001x" — a Float formatted with toString
// leaks its representation into the UI.
private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
