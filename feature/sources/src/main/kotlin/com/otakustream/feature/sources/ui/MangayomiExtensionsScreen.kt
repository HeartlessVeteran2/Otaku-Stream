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
import androidx.compose.material3.OutlinedButton
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
import com.otakustream.core.sources.mangayomi.repo.MangayomiExtensionListing

@Composable
fun MangayomiExtensionsScreen(
    modifier: Modifier = Modifier,
    viewModel: MangayomiExtensionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = "AnymeX extensions", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Install AnymeX/Mangayomi anime extensions from a repository. Paste a repo's " +
                    "anime index URL, or use the built-in example. The app ships no sources itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = uiState.repoUrl,
                    onValueChange = viewModel::onRepoUrlChange,
                    label = { Text("Extension repo URL") },
                    supportingText = { Text("Link to a Mangayomi anime index (JSON). Leave blank for the built-in example.") },
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

            if (!uiState.isLoading && uiState.error == null && uiState.listings.isEmpty()) {
                Text(
                    text = "No extensions listed in this repository.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(uiState.listings, key = { it.id }) { listing ->
                    MangayomiExtensionRow(
                        listing = listing,
                        isInstalled = listing.id in uiState.installedIds,
                        isInstalling = uiState.installingId == listing.id,
                        canInstall = uiState.installingId == null,
                        onInstall = { viewModel.install(listing) },
                        onUninstall = { viewModel.uninstall(listing) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MangayomiExtensionRow(
    listing: MangayomiExtensionListing,
    isInstalled: Boolean,
    isInstalling: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(listing.name) },
        supportingContent = {
            val nsfw = if (listing.isNsfw) " · 18+" else ""
            Text(
                text = "${listing.lang} · v${listing.version}$nsfw",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            when {
                isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                isInstalled -> OutlinedButton(onClick = onUninstall) { Text("Remove") }
                else -> Button(onClick = onInstall, enabled = canInstall) { Text("Install") }
            }
        },
    )
}
