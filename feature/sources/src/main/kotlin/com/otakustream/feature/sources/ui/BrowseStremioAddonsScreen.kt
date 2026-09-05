package com.otakustream.feature.sources.ui

import android.content.Intent
import android.net.Uri


import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.core.sources.stremio.normalizeStremioManifestUrl
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
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopBar(title = "Add-on directory", onBack = onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // The header is one item rather than several: it is a fixed run of content always
            // composed together, so splitting it would buy no laziness.
            item {
                Column {
                    Text(
                        text = "Add-ons recommended for anime lead the list; below them is Stremio's own " +
                            "official and community directory. Note that Stremio's lists carry no stream " +
                            "add-ons at all — everything that resolves a video is in the first group.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    FilterRow(selected = uiState.filter, onSelect = viewModel::setFilter)

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
                    isInstalled = normalizeStremioManifestUrl(listing.transportUrl) in uiState.installedUrls,
                    isInstalling = uiState.installingUrl == listing.transportUrl,
                    canInstall = uiState.installingUrl == null,
                    onInstall = { viewModel.install(listing) },
                    onConfigure = { url ->
                        // Best-effort: a device with no browser at all would throw, and failing to
                        // open a help page is not worth crashing the directory over.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )
            }
        }
    }
}

// Narrows a hundred rows to the handful worth reading.
//
// Stremio's community collection is roughly half subtitle add-ons, and scrolling past fifty of them
// to find the two that stream something is the reason the directory felt empty when it was in fact
// full.
@Composable
private fun FilterRow(selected: AddonFilter, onSelect: (AddonFilter) -> Unit) {
    // Horizontally scrollable, not a plain Row. Four chips fit on a typical phone at the default
    // font scale and stop fitting at larger ones — and a chip that overflows a Row is not merely
    // ugly, it is clipped and unreachable, so "Subtitles" would silently become unselectable for
    // exactly the users who most need a bigger font.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 12.dp),
    ) {
        AddonFilter.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
            )
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
    onConfigure: (String) -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listing.name, modifier = Modifier.weight(1f, fill = false))
                OriginChip(listing.origin)
            }
        },
        supportingContent = {
            Column {
                listing.description?.let { Text(it) }
                // Said before installing, not discovered after. An add-on that serves nothing until
                // it is configured installs perfectly happily and then returns no streams forever,
                // which is indistinguishable from the app being broken.
                if (listing.configurationRequired) {
                    Text(
                        text = "Needs configuring before it returns anything.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            CoverImage(
                url = listing.logoUrl,
                contentDescription = listing.name,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
        },
        trailingContent = {
            val configureUrl = listing.configureUrl
            Column(horizontalAlignment = Alignment.End) {
                when {
                    isInstalled -> Text("Installed", style = MaterialTheme.typography.bodySmall)
                    isInstalling -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    // Configure *replaces* Install rather than sitting beside it when the add-on
                    // serves nothing until configured. Offering Install there offers the one action
                    // guaranteed not to work: it succeeds, and leaves a source that returns nothing
                    // forever. Configuring hands back a different, personalised manifest URL, which
                    // is what actually gets installed — so this row's Install was never the route.
                    // Branching on configurationRequired alone, then on whether there is a URL to
                    // send them to. Requiring both in the condition let an add-on with an
                    // unparseable transport URL fall through to Install — putting the button back
                    // beside the "needs configuring" warning, which is the exact pairing this
                    // branch exists to prevent.
                    listing.configurationRequired -> if (configureUrl != null) {
                        // No `enabled = canInstall`: this opens a browser and installs nothing, so
                        // gating it on an unrelated add-on's in-flight install would disable it for
                        // no reason — and the optional Configure link below is ungated already.
                        Button(onClick = { onConfigure(configureUrl) }) { Text("Configure") }
                    } else {
                        // Nothing safe to offer: it cannot be configured, and installing it is
                        // the one action known not to work.
                        Text(
                            text = "Unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Button(onClick = onInstall, enabled = canInstall) { Text("Install") }
                }
                // A secondary link where configuring is optional — Torrentio and Comet work
                // unconfigured, and configuring adds a debrid service or narrows the trackers.
                // Opens in a browser because the page is arbitrary HTML served by the add-on;
                // that round trip is how Stremio itself configures these.
                if (!listing.configurationRequired && configureUrl != null) {
                    TextButton(onClick = { onConfigure(configureUrl) }) {
                        Text("Configure", style = MaterialTheme.typography.labelSmall)
                    }
                }
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
        AddonListOrigin.RECOMMENDED -> MaterialTheme.colorScheme.primary
        AddonListOrigin.OFFICIAL -> MaterialTheme.colorScheme.primary
        AddonListOrigin.COMMUNITY -> MaterialTheme.colorScheme.tertiary
        AddonListOrigin.CUSTOM -> MaterialTheme.colorScheme.secondary
    }
    val label = when (origin) {
        AddonListOrigin.RECOMMENDED -> "For anime"
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
