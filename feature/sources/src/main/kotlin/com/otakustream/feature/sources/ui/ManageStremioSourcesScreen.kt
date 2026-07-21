package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ManageStremioSourcesScreen(
    prefillInstallUrl: String? = null,
    onBrowseAddonsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ManageStremioSourcesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val serverBaseUrl by viewModel.serverBaseUrl.collectAsState()
    var serverUrlInput by remember { mutableStateOf("") }

    // stremio:// deep links land here with the addon's manifest URL pre-resolved — install it
    // right away rather than making the user paste it again.
    LaunchedEffect(prefillInstallUrl) {
        prefillInstallUrl?.let { url ->
            viewModel.onUrlInputChange(url)
            viewModel.install()
        }
    }

    var showAdvanced by remember(serverBaseUrl) { mutableStateOf(serverBaseUrl != null) }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Add an add-on", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onBrowseAddonsClick) { Text("Browse add-ons") }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = viewModel::onUrlInputChange,
                    label = { Text("Add-on link") },
                    supportingText = { Text("Paste an add-on's install link, or tap Browse add-ons.") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = viewModel::install, enabled = !uiState.isInstalling) {
                    Text("Add")
                }
            }

            if (uiState.isInstalling) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }

            uiState.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::install, enabled = !uiState.isInstalling) { Text("Retry") }
                }
            }

            Text(text = "Installed add-ons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

            if (uiState.addons.isEmpty()) {
                Text(
                    text = "No add-ons installed yet. Browse the directory or paste an add-on link above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(uiState.addons, key = { _, item -> item.record.manifestUrl }) { index, item ->
                    AddonRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.addons.lastIndex,
                        onToggleEnabled = { viewModel.toggleAddonEnabled(item) },
                        onMoveUp = { viewModel.moveAddon(item, -1) },
                        onMoveDown = { viewModel.moveAddon(item, 1) },
                        onToggleCatalog = { catalog -> viewModel.toggleCatalogEnabled(item, catalog) },
                        onRemove = { viewModel.remove(item.record) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Streaming server is a power-user setting most people never touch — tuck it behind an
            // "Advanced" toggle so it doesn't clutter the main add-on flow.
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced (streaming server)")
            }
            if (showAdvanced) {
                Text(
                    text = "Only some add-ons need this. If an add-on tells you to set a streaming " +
                        "server address, paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (serverBaseUrl != null) {
                    Text(
                        text = "✓ Server set: $serverBaseUrl",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = viewModel::clearServerUrl) { Text("Remove") }
                } else {
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        label = { Text("Streaming server address") },
                        supportingText = { Text("e.g. http://100.x.x.x:11470") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = { viewModel.saveServerUrl(serverUrlInput); serverUrlInput = "" },
                            enabled = serverUrlInput.isNotBlank(),
                        ) { Text("Save") }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    item: StremioAddonItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleCatalog: (StremioCatalogItem) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.record.name, style = MaterialTheme.typography.titleSmall)
                Text(text = item.record.manifestUrl, style = MaterialTheme.typography.bodySmall)
                // Add-ons with no browsable catalog (Torrentio etc.) still contribute streams to
                // playback — make that clear so they don't look inert.
                if (item.catalogs.isEmpty()) {
                    Text(
                        text = "Streams only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
            Switch(checked = item.record.enabled, onCheckedChange = { onToggleEnabled() })
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        item.catalogs.forEach { catalog ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
            ) {
                Text(text = catalog.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = catalog.enabled,
                    onCheckedChange = { onToggleCatalog(catalog) },
                    enabled = item.record.enabled,
                )
            }
        }
    }
}
