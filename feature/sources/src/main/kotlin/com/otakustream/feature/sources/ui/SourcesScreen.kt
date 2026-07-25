package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// One place to add and manage every kind of source. Previously the three install flows — Stremio
// add-ons, AnymeX/Mangayomi extensions, and script sources — were scattered as sibling rows in
// Settings and behind the Catalog gear; this hub collects them under "Browse & install" (find
// something new) and "Installed" (manage what you have) so there's a single answer to "where do I
// add a source?". It only navigates — the underlying installers are unchanged.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onBrowseAddons: () -> Unit,
    onBrowseInstallableSources: () -> Unit,
    onManageAddons: () -> Unit,
    onAnymexExtensions: () -> Unit,
    onCustomSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Browse & install")
            ListItem(
                headlineContent = { Text("Add-on directory") },
                supportingContent = { Text("Browse Stremio add-ons that fill your catalog") },
                modifier = Modifier.clickable(onClick = onBrowseAddons),
            )
            ListItem(
                headlineContent = { Text("Installable sources") },
                supportingContent = { Text("One-tap install from a curated source catalog") },
                modifier = Modifier.clickable(onClick = onBrowseInstallableSources),
            )

            SectionHeader("Installed")
            ListItem(
                headlineContent = { Text("Add-ons") },
                supportingContent = { Text("Enable, reorder, or remove installed Stremio add-ons") },
                modifier = Modifier.clickable(onClick = onManageAddons),
            )
            ListItem(
                headlineContent = { Text("AnymeX extensions") },
                supportingContent = { Text("Install anime extensions from an AnymeX/Mangayomi repository") },
                modifier = Modifier.clickable(onClick = onAnymexExtensions),
            )
            ListItem(
                headlineContent = { Text("Custom sources") },
                supportingContent = { Text("Advanced: add script-based video sources by URL") },
                modifier = Modifier.clickable(onClick = onCustomSources),
            )
        }
    }
}

// Small section label shared by the Sources hub and the Settings groups.
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
