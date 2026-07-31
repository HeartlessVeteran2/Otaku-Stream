package com.otakustream.feature.sources.ui


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.OfficialAddonListing
import com.otakustream.core.ui.CoverImage

@Composable
fun BrowseStremioAddonsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: BrowseStremioAddonsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopBar(title = "Add-on directory", onBack = onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // The header is one item rather than several: it is a fixed run of content always
            // composed together, so splitting it would buy no laziness.
            item(key = "header") {
                Column {
                    Text(
                        text = "Browse official and community add-ons and tap Install to add them to your catalog.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    CustomListField(
                        savedUrl = uiState.customListUrl,
                        error = uiState.customListError,
                        onSave = viewModel::saveCustomListUrl,
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
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

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

// Optional third-party list. Deliberately a URL the user supplies rather than a picker of
// hard-coded community sites: those have no stable, documented JSON shape (the ones commonly
// suggested serve either nothing at that path or a JavaScript app), so pinning parsers to them
// would break silently. Anything serving Stremio's own collection shape works here.
@Composable
private fun CustomListField(
    savedUrl: String,
    error: String?,
    onSave: (String) -> Unit,
) {
    // rememberSaveable: a half-typed URL survives a rotation or a process-death restore, which
    // plain remember would discard.
    var draft by rememberSaveable { mutableStateOf(savedUrl) }
    // The saved value arrives asynchronously from prefs, so it can land after the user has already
    // started typing. Adopt it only while the field is untouched — comparing draft to savedUrl would
    // detect an edit and then overwrite it, which is the opposite of what's wanted.
    var edited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(savedUrl) {
        if (!edited) draft = savedUrl
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; edited = true },
            label = { Text("Add your own list (optional)") },
            placeholder = { Text("https://…") },
            supportingText = {
                Text("A URL serving a Stremio add-on collection. Leave empty to use only the built-in lists.")
            },
            singleLine = true,
            isError = error != null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { edited = false; onSave(draft) },
                enabled = draft != savedUrl,
            ) { Text("Save list") }
            if (savedUrl.isNotBlank()) {
                TextButton(onClick = { draft = ""; edited = false; onSave("") }) { Text("Remove") }
            }
        }
        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listing.name, modifier = Modifier.weight(1f, fill = false))
                OriginChip(listing.origin)
            }
        },
        supportingContent = { listing.description?.let { Text(it) } },
        leadingContent = {
            CoverImage(
                url = listing.logoUrl,
                contentDescription = listing.name,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
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

// Which list this add-on came from. The three differ in how vetted they are — Stremio curates the
// official one, the community collection is broader, and a custom list is whatever the user pointed
// at — so someone about to install something can see which they're looking at rather than guess.
@Composable
private fun OriginChip(origin: AddonListOrigin) {
    val color = when (origin) {
        AddonListOrigin.OFFICIAL -> MaterialTheme.colorScheme.primary
        AddonListOrigin.COMMUNITY -> MaterialTheme.colorScheme.tertiary
        AddonListOrigin.CUSTOM -> MaterialTheme.colorScheme.secondary
    }
    val label = when (origin) {
        AddonListOrigin.OFFICIAL -> "Official"
        AddonListOrigin.COMMUNITY -> "Community"
        AddonListOrigin.CUSTOM -> "Custom list"
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}
