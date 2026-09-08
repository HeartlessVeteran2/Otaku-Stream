package com.otakustream.feature.sources.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.otakustream.core.ui.BackTopBar
import com.otakustream.core.ui.SectionHeader

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
            BackTopBar(title = "Sources", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Browse & install")
            ListItem(
                headlineContent = { Text("Add-on directory") },
                supportingContent = { Text("Browse Stremio add-ons that fill your catalog") },
                modifier = Modifier.clickable(role = Role.Button, onClick = onBrowseAddons),
            )
            ListItem(
                headlineContent = { Text("Source directory") },
                supportingContent = { Text("One-tap install from a curated directory") },
                modifier = Modifier.clickable(role = Role.Button, onClick = onBrowseInstallableSources),
            )

            SectionHeader("Installed")
            ListItem(
                headlineContent = { Text("Add-ons") },
                supportingContent = { Text("Enable, reorder, or remove installed Stremio add-ons") },
                modifier = Modifier.clickable(role = Role.Button, onClick = onManageAddons),
            )
            ListItem(
                headlineContent = { Text("Extensions") },
                // Names the format, because the obvious guess is wrong and costs an afternoon:
                // these are Mangayomi/AnymeX JavaScript extensions, and Aniyomi's .apk extensions —
                // which is what most people mean by "anime extensions" — cannot be loaded here at
                // all. Different ecosystem, many of the same sites.
                supportingContent = { Text("JavaScript extensions from AnymeX/Mangayomi repos (not Aniyomi .apk files)") },
                modifier = Modifier.clickable(role = Role.Button, onClick = onAnymexExtensions),
            )
            ListItem(
                headlineContent = { Text("Custom sources") },
                supportingContent = { Text("Advanced: add script-based sources by link") },
                modifier = Modifier.clickable(role = Role.Button, onClick = onCustomSources),
            )
        }
    }
}
