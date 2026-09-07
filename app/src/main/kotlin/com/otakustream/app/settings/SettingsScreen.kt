package com.otakustream.app.settings

// Lifted out of AppNavHost, which had grown a hundred-line private composable for the Settings tab
// alongside the navigation graph — two unrelated things in one file, and the reason Settings kept
// being the screen nobody extended.

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.otakustream.app.ui.theme.AppearanceViewModel
import com.otakustream.app.ui.theme.ThemeMode
import com.otakustream.core.ui.SectionHeader
import com.otakustream.feature.sources.ui.CloudflareSettingRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSourcesClick: () -> Unit,
    onPlaybackClick: () -> Unit,
    onTrackingClick: () -> Unit,
    onStremioAccountClick: () -> Unit,
) {
    // Same fake-title problem the Library tab had: a Text styled like a title, with its own padding,
    // instead of the TopAppBar every other screen uses. The scroll container moves inside so the
    // bar stays put while the list moves under it.
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Content")
        ListItem(
            headlineContent = { Text("Sources") },
            supportingContent = { Text("Find and manage your sources") },
            modifier = Modifier.clickable(onClick = onSourcesClick),
        )

        SectionHeader("Playback")
        ListItem(
            headlineContent = { Text("Playback") },
            // Says what is behind the row, because the point of adding it is that none of this was
            // findable: every one of these lived inside the player's own menus, reachable only
            // while something was already playing.
            supportingContent = { Text("Skip intros, seek step, default speed, subtitle style") },
            modifier = Modifier.clickable(onClick = onPlaybackClick),
        )

        SectionHeader("Appearance")
        ThemeModeRow()

        SectionHeader("Accounts & sync")
        ListItem(
            headlineContent = { Text("AniList tracking") },
            supportingContent = { Text("Sync watch progress to your AniList account") },
            modifier = Modifier.clickable(onClick = onTrackingClick),
        )
        ListItem(
            headlineContent = { Text("Stremio account") },
            supportingContent = { Text("Sign in to sync your Stremio library") },
            modifier = Modifier.clickable(onClick = onStremioAccountClick),
        )

        SectionHeader("Advanced")
        CloudflareSettingRow()

        SectionHeader("About")
        val context = LocalContext.current
        // Read the version from the package rather than BuildConfig so :app doesn't need the
        // buildConfig feature turned on — same approach the crash reporter already uses.
        val versionName = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }
        ListItem(
            headlineContent = { Text("Otaku Stream") },
            supportingContent = { Text(if (versionName.isBlank()) "Version unavailable" else "Version $versionName") },
        )
        ListItem(
            headlineContent = { Text("Source code & releases") },
            supportingContent = { Text(PROJECT_URL) },
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                }
            },
        )
        Text(
            text = "Otaku Stream is a player and library app: it ships no content and no " +
                "third-party add-ons. What you play with it is up to you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        )
        }
    }
}

// Dark, light, or whatever the phone is set to. Three chips rather than a switch because there
// are genuinely three answers: a switch would have to drop "follow the system", which is the
// default and the one most people want.
@Composable
private fun ThemeModeRow(viewModel: AppearanceViewModel = hiltViewModel()) {
    val mode by viewModel.themeMode.collectAsState()
    ListItem(
        headlineContent = { Text("Theme") },
        supportingContent = {
            // Scrollable rather than a fixed Row: at a large font scale three chips do not fit a
            // narrow window, and a clipped chip is an option the user cannot reach. Same bug, same
            // fix as the add-on directory's filter row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            ) {
                ThemeMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { viewModel.setThemeMode(option) },
                        label = { Text(option.label()) },
                    )
                }
            }
        },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

private const val PROJECT_URL = "https://github.com/HeartlessVeteran2/Otaku-Stream"
