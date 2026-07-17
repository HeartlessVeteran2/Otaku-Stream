package com.otakustream.feature.sources.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            Text(text = "Official Stremio addons", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Curated directly from Stremio's own addon collection.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }

            uiState.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(uiState.listings, key = { it.transportUrl }) { listing ->
                    AddonListingRow(
                        listing = listing,
                        isInstalled = listing.transportUrl in uiState.installedUrls,
                        isInstalling = uiState.installingUrl == listing.transportUrl,
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
    onInstall: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(listing.name) },
        supportingContent = { listing.description?.let { Text(it) } },
        trailingContent = {
            when {
                isInstalled -> Text("Installed", style = MaterialTheme.typography.bodySmall)
                isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                else -> Button(onClick = onInstall) { Text("Install") }
            }
        },
    )
}
