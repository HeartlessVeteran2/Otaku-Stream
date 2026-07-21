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
import com.otakustream.feature.sources.SourceCatalogEntry

@Composable
fun BrowseSourceCatalogScreen(
    modifier: Modifier = Modifier,
    viewModel: BrowseSourceCatalogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = "Browse sources", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "One-tap install sources from a directory. Point at a source repository, or " +
                    "use the built-in example.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.repoUrl,
                    onValueChange = viewModel::onRepoUrlChange,
                    label = { Text("Source repository URL") },
                    supportingText = { Text("A link to a source directory (JSON). Leave blank for the built-in list.") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = viewModel::saveRepoUrl, enabled = !uiState.isLoading) { Text("Load") }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }

            uiState.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::load) { Text("Retry") }
                }
            }

            if (!uiState.isLoading && uiState.error == null && uiState.entries.isEmpty()) {
                Text(
                    text = "No sources listed in this directory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(uiState.entries, key = { it.url }) { entry ->
                    SourceCatalogRow(
                        entry = entry,
                        isInstalled = entry.url in uiState.installedUrls,
                        isInstalling = uiState.installingUrl == entry.url,
                        canInstall = uiState.installingUrl == null,
                        onInstall = { viewModel.install(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCatalogRow(
    entry: SourceCatalogEntry,
    isInstalled: Boolean,
    isInstalling: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Column {
                entry.description?.let { Text(it) }
                Text(
                    text = "${entry.type} · ${entry.lang}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                isInstalled -> Text("Installed", style = MaterialTheme.typography.bodySmall)
                isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                else -> Button(onClick = onInstall, enabled = canInstall) { Text("Install") }
            }
        },
    )
}
