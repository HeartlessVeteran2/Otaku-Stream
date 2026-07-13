package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ManageStremioSourcesScreen(
    modifier: Modifier = Modifier,
    viewModel: ManageStremioSourcesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val serverBaseUrl by viewModel.serverBaseUrl.collectAsState()
    var serverUrlInput by remember { mutableStateOf("") }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = "Streaming server", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Only needed for addons that return torrent-based streams. Point this at a " +
                    "self-hosted Stremio streaming server, e.g. http://100.x.x.x:11470.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (serverBaseUrl != null) {
                Text(
                    text = "✓ Server configured: $serverBaseUrl",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = viewModel::clearServerUrl) { Text("Remove") }
            } else {
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    label = { Text("Streaming server URL") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { viewModel.saveServerUrl(serverUrlInput); serverUrlInput = "" }) { Text("Save") }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            Text(text = "Add an addon by manifest URL", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = viewModel::onUrlInputChange,
                    label = { Text("Manifest URL") },
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
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            Text(text = "Installed addons", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

            LazyColumn {
                items(uiState.addons, key = { it.manifestUrl }) { record ->
                    ListItem(
                        headlineContent = { Text(record.name) },
                        supportingContent = { Text(record.manifestUrl) },
                        trailingContent = {
                            TextButton(onClick = { viewModel.remove(record) }) {
                                Text("Remove")
                            }
                        },
                    )
                }
            }
        }
    }
}
