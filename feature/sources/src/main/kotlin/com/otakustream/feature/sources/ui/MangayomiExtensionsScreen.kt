package com.otakustream.feature.sources.ui

import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.mangayomi.repo.MangayomiExtensionListing
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.ConfirmDialog
import com.otakustream.core.ui.EmptyState

@Composable
fun MangayomiExtensionsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onConfigure: (Long) -> Unit = {},
    viewModel: MangayomiExtensionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Removing drops the extension and its saved preferences, and getting it back means fetching
    // it from the repository again — not an undo, so it asks.
    //
    // Worded "Remove" to match the button that opens it, and the sibling screens. The row said
    // Remove and the dialog said Uninstall, which is two names for one action on one screen.
    var pendingUninstall by remember { mutableStateOf<MangayomiExtensionListing?>(null) }
    pendingUninstall?.let { listing ->
        ConfirmDialog(
            title = "Remove ${listing.name}?",
            body = "The extension and any preferences you set for it are removed. You can install " +
                "it again from this list.",
            confirmLabel = "Remove",
            onConfirm = { viewModel.uninstall(listing) },
            onDismiss = { pendingUninstall = null },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopBar(title = "Extensions", onBack = onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // The header is one item rather than several: it is a fixed run of content always
            // composed together, so splitting it would buy no laziness.
            item {
                Column {
                    Text(
                        text = "Mangayomi/AnymeX extensions, written in JavaScript. Aniyomi's own " +
                            "extensions are Android apps and are a different format this app can't " +
                            "load, even where they cover the same sites.\n\n" +
                            "The repositories below are loaded for you. Paste another index URL to " +
                            "add one of your own. The app ships no sources itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    // Tappable, not just named. All three are merged into the list below already,
                    // so this is for the case where you want to see one repo on its own — and it is
                    // also what makes their URLs discoverable at all, since the alternative was
                    // knowing one to type.
                    if (uiState.suggestedRepos.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                        ) {
                            uiState.suggestedRepos.forEach { repo ->
                                AssistChip(
                                    onClick = { viewModel.useSuggestedRepo(repo) },
                                    label = { Text(repo.name) },
                                    enabled = !uiState.isLoading,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = uiState.repoUrl,
                            onValueChange = viewModel::onRepoUrlChange,
                            label = { Text("Extension repo URL") },
                            supportingText = { Text("Optional — an extra anime_index.json to merge with the ones above.") },
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

                    // What the list does not contain, and why.
                    //
                    // parseMangayomiIndex keeps only anime extensions written in JavaScript; the
                    // biggest curated repo is mostly Dart. Rendering 24 rows out of 64 with no
                    // explanation looks exactly like a repo that is broken or half-loaded.
                    if (uiState.unsupportedCount > 0) {
                        Text(
                            text = "${uiState.listings.size} shown · ${uiState.unsupportedCount} " +
                                "entries are Dart extensions or manga sources, which this app can't run.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    // A banner, not a blank screen: the other repos' extensions are still listed.
                    if (uiState.unreachableRepos.isNotEmpty()) {
                        Text(
                            text = "Couldn't reach ${uiState.unreachableRepos.joinToString(", ")}. " +
                                "Showing what loaded.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    if (!uiState.isLoading && uiState.error == null && uiState.listings.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.Extension,
                            title = "No extensions here",
                            message = "This repository's index came back empty. Try one of the " +
                                "recommended repositories instead.",
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            items(uiState.listings, key = { it.id }) { listing ->
                MangayomiExtensionRow(
                    listing = listing,
                    isInstalled = listing.id in uiState.installedIds,
                    isInstalling = uiState.installingId == listing.id,
                    canInstall = uiState.installingId == null,
                    onInstall = { viewModel.install(listing) },
                    onUninstall = { pendingUninstall = listing },
                    onConfigure = { onConfigure(listing.id) },
                )
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
    onConfigure: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(listing.name) },
        supportingContent = {
            val nsfw = if (listing.isNsfw) " · 18+" else ""
            val repo = listing.repoName?.let { " · $it" }.orEmpty()
            Text(
                text = "${listing.lang} · v${listing.version}$repo$nsfw",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            when {
                isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onConfigure) {
                        Icon(Icons.Default.Settings, contentDescription = "Preferences")
                    }
                    OutlinedButton(onClick = onUninstall) { Text("Remove") }
                }
                else -> Button(onClick = onInstall, enabled = canInstall) { Text("Install") }
            }
        },
    )
}
