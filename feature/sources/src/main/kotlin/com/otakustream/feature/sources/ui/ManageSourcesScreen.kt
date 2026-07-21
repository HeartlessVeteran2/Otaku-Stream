package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ManageSourcesScreen(
    onBrowseCatalogClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageSourcesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Button(onClick = onBrowseCatalogClick, modifier = Modifier.fillMaxWidth()) {
                Text("Browse source catalog")
            }
            Text(
                text = "Install sources one-tap from a directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Text(text = "Add a custom source", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Advanced: add-ons (recommended) come from the directory. Custom sources are " +
                    "script-based and for advanced users.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = viewModel::onUrlInputChange,
                    label = { Text("Source script link") },
                    supportingText = { Text("A link to a source script (.js).") },
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

            Text(text = "Installed sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

            if (uiState.installed.isEmpty()) {
                Text(
                    text = "No custom sources yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            LazyColumn {
                items(uiState.installed, key = { it.scriptUrl }) { record ->
                    ListItem(
                        headlineContent = { Text(record.name) },
                        supportingContent = { Text(record.scriptUrl) },
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
