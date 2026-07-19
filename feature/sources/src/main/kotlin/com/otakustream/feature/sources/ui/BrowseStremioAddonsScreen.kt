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
import com.otakustream.core.sources.stremio.model.OfficialAddonListing

@Composable
fun BrowseStremioAddonsScreen(
    modifier: Modifier = Modifier,
    viewModel: BrowseStremioAddonsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text = "Add-on directory", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Browse add-ons and tap Install to add them to your catalog.",
                style = MaterialTheme.typography.bodySmall,
            )

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
                    text = "Couldn't find any add-ons right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(uiState.listings, key = { it.transportUrl }) { listing ->
                    AddonListingRow(
                        listing = listing,
                        isInstalled = listing.transportUrl in uiState.installedUrls,
                        isInstalling = uiState.installingUrl == listing.transportUrl,
                        canInstall = uiState.installingUrl == null,
                        onInstall = { viewModel.install(listing) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonListingRow(
    listing: OfficialAddonListing,
    isInstalled: Boolean,
    isInstalling: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(listing.name) },
        supportingContent = { listing.description?.let { Text(it) } },
        trailingContent = {
            when {
                isInstalled -> Text("Installed", style = MaterialTheme.typography.bodySmall)
                isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                else -> Button(onClick = onInstall, enabled = canInstall) { Text("Install") }
            }
        },
    )
}
