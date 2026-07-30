package com.otakustream.feature.sources.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState

// Torrent settings, shown next to the streaming-server field because both answer the same question —
// "how should a torrent-backed stream be played?" — and keeping them apart would make them look like
// unrelated features.
@Composable
fun TorrentSettingsSection(
    modifier: Modifier = Modifier,
    viewModel: TorrentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(text = "Play torrents on this device", style = MaterialTheme.typography.titleSmall)

        if (!state.deviceSupported) {
            // Not a failure to apologise for, but the user needs to know why the toggle isn't there:
            // only the 64-bit torrent library is bundled, to keep the download size down.
            Text(
                text = "Not available on this device — the torrent engine needs a 64-bit processor. " +
                    "Torrent streams will still play through a streaming server if you set one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        Text(
            text = "Plays torrent streams directly, with no streaming server needed. " +
                "Downloading a torrent shares your IP address with the other people in its swarm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(text = "Enabled", modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
        }

        if (state.enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Named for what the engine actually checks — whether the connection is metered —
                    // rather than for Wi-Fi. Those differ in both directions: a Wi-Fi hotspot can be
                    // marked metered, and Ethernet on a TV box is unmetered but isn't Wi-Fi. Calling
                    // it "Wi-Fi only" would promise something this doesn't enforce.
                    Text(text = "Only on unmetered networks")
                    Text(
                        text = "Torrents fetch ahead and talk to many peers, so they use much more " +
                            "data than a normal stream. Usually this means Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.unmeteredOnly, onCheckedChange = viewModel::setUnmeteredOnly)
            }

            Text(
                text = "Storage limit",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            // Scrollable: at the largest accessibility font sizes three chips no longer fit across a
            // narrow screen, and a clipped chip is a choice the user can't make.
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                state.quotaChoices.forEach { choice ->
                    FilterChip(
                        selected = choice.bytes == state.quotaBytes,
                        onClick = { viewModel.setQuota(choice.bytes) },
                        label = { Text(choice.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        // Outside the `enabled` block on purpose. Turning the feature off doesn't delete what it
        // already downloaded, so hiding these would strand gigabytes with no way to reclaim them from
        // the one screen that mentions them.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                text = "Using ${state.usageLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = viewModel::clearCache,
                enabled = state.usageBytes > 0 && !state.isClearing,
            ) { Text(if (state.isClearing) "Clearing…" else "Clear now") }
        }
    }
}
