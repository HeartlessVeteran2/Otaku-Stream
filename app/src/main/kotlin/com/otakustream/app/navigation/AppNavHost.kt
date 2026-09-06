package com.otakustream.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.otakustream.app.ui.theme.AppearanceViewModel
import com.otakustream.app.ui.theme.OtakuStreamTheme
import com.otakustream.app.ui.theme.ThemeMode
import com.otakustream.core.player.ui.PlayerScreen
import com.otakustream.core.sources.api.UiMessages
import com.otakustream.feature.library.LibraryScreen
import com.otakustream.feature.sources.ui.AniListDetailScreen
import com.otakustream.feature.sources.ui.AiringScheduleScreen
import com.otakustream.feature.sources.ui.AniListSearchScreen
import com.otakustream.feature.sources.ui.AniListWatchScreen
import com.otakustream.feature.sources.ui.BrowseSourceCatalogScreen
import com.otakustream.feature.sources.ui.BrowseStremioAddonsScreen
import com.otakustream.feature.sources.ui.CatalogScreen
import com.otakustream.feature.sources.ui.CloudflareSettingRow
import com.otakustream.feature.sources.ui.MangayomiExtensionsScreen
import com.otakustream.feature.sources.ui.MangayomiPreferencesScreen
import com.otakustream.feature.sources.ui.ManageSourcesScreen
import com.otakustream.feature.sources.ui.ManageStremioSourcesScreen
import com.otakustream.feature.sources.ui.MediaDetailsScreen
import com.otakustream.feature.sources.ui.SectionHeader
import com.otakustream.feature.sources.ui.SourcesScreen
import com.otakustream.feature.sources.ui.StremioAccountScreen
import com.otakustream.feature.tracking.TrackingSettingsScreen
import kotlinx.coroutines.launch

private const val ROUTE_PLAY = "play"
private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SOURCES = "sources"
private const val ROUTE_MANAGE_SOURCES = "manage-sources"
private const val ROUTE_TRACKING_SETTINGS = "tracking-settings"
private const val ROUTE_MANAGE_STREMIO = "manage-stremio"
private const val ROUTE_STREMIO_ACCOUNT = "stremio-account"
private const val ROUTE_MANAGE_STREMIO_PATTERN = "manage-stremio?installUrl={installUrl}"
private const val ROUTE_BROWSE_STREMIO = "browse-stremio"
private const val ROUTE_BROWSE_SOURCE_CATALOG = "browse-source-catalog"
private const val ROUTE_ANYMEX_EXTENSIONS = "anymex-extensions"
private const val ROUTE_ANYMEX_EXTENSION_PREFS = "anymex-extension-prefs/{sourceId}"
private const val ROUTE_DETAILS = "details/{sourceId}?mediaUrl={mediaUrl}&title={title}&coverUrl={coverUrl}"
private const val ROUTE_ANILIST_DETAILS = "anilist/{mediaId}"
private const val ROUTE_ANILIST_WATCH = "anilist-watch/{mediaId}?title={title}"
private const val ROUTE_ANILIST_SEARCH = "anilist-search"
private const val ROUTE_AIRING_SCHEDULE = "airing-schedule"
// fromSource rides on the route so it survives process death. PendingPlayback is in-memory, and the
// back stack is not: after the OS kills the app and the user returns, this route is restored with its
// arguments while the stash is gone. Without the flag, a source-supplied url would come back looking
// like the user's own choice and be judged by the permissive rules. Only ever tightens — absent means
// "not known to be from a source", which is what every user-initiated entry point is.
private const val ROUTE_PLAYER = "player?videoUrl={videoUrl}&fromSource={fromSource}"

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(ROUTE_PLAY, "Play", Icons.Filled.PlayCircle),
    BottomTab(ROUTE_CATALOG, "Browse", Icons.Filled.Explore),
    BottomTab(ROUTE_LIBRARY, "Library", Icons.Filled.VideoLibrary),
    BottomTab(ROUTE_SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNavHost(
    pendingStremioInstallUrl: String? = null,
    onPendingStremioInstallUrlConsumed: () -> Unit = {},
    pendingPlayUrl: String? = null,
    onPendingPlayUrlConsumed: () -> Unit = {},
    pendingAniListToken: String? = null,
    pendingAniListState: String? = null,
    onPendingAniListTokenConsumed: () -> Unit = {},
    pendingMagnetName: String? = null,
    onMagnetConfirmed: () -> Unit = {},
    onMagnetDismissed: () -> Unit = {},
    // Whether the app's own scheme is dark. Combined with the destination below to decide the
    // system bar icon style, which the activity applies.
    appThemeIsDark: Boolean = true,
    onSystemBarsDarkChanged: (Boolean) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The player renders in the dark scheme whatever the app is set to, so in a light app its
    // transparent status bar would otherwise draw dark icons over black video.
    //
    // SideEffect, not LaunchedEffect: this runs in the apply phase of the same composition that
    // puts the player on screen, before that frame is drawn. A LaunchedEffect would dispatch a
    // coroutine after the composition committed, leaving the bars a frame behind the video.
    // Guarded on the last value applied because SideEffect runs on every recomposition and each
    // call re-registers a window listener.
    val barsDark = appThemeIsDark || currentRoute == ROUTE_PLAYER
    var appliedBarsDark by remember { mutableStateOf<Boolean?>(null) }
    SideEffect {
        if (appliedBarsDark != barsDark) {
            appliedBarsDark = barsDark
            onSystemBarsDarkChanged(barsDark)
        }
    }
    val showBottomBar = currentRoute == ROUTE_PLAY || currentRoute == ROUTE_CATALOG ||
        currentRoute == ROUTE_LIBRARY || currentRoute == ROUTE_SETTINGS

    LaunchedEffect(pendingStremioInstallUrl) {
        pendingStremioInstallUrl?.let { url ->
            navController.navigate("manage-stremio?installUrl=${Uri.encode(url)}")
            onPendingStremioInstallUrlConsumed()
        }
    }

    // A file/video-link opened via "Open with" should land straight in the player, not the Play
    // tab — same as how the stremio:// deep link above skips Settings and goes to manage-stremio.
    LaunchedEffect(pendingPlayUrl) {
        pendingPlayUrl?.let { url ->
            navController.navigate("player?videoUrl=${Uri.encode(url)}")
            onPendingPlayUrlConsumed()
        }
    }

    // Returning from the AniList sign-in page: land on the tracking screen, which reads the
    // token (still held here) and persists it. The token is consumed by that screen, not here,
    // so it can't be lost between the navigate and the screen's first composition.
    LaunchedEffect(pendingAniListToken) {
        if (pendingAniListToken != null) {
            navController.navigate(ROUTE_TRACKING_SETTINGS) { launchSingleTop = true }
        }
    }

    // A magnet handed to the app from outside is asked about before anything starts.
    //
    // Every other deep link this app accepts either navigates somewhere the user can back out of or
    // fetches over HTTP to one host. Starting a torrent is different in kind: it announces the
    // device to a swarm of strangers and begins uploading, and it is not undone by pressing back. A
    // page the user is merely browsing can hand this app a magnet: link, so the app asks first.
    if (pendingMagnetName != null) {
        AlertDialog(
            onDismissRequest = onMagnetDismissed,
            title = { Text("Download this torrent?") },
            text = {
                Text(
                    "Another app asked Otaku Stream to play:\n\n$pendingMagnetName\n\n" +
                        "Downloading connects you to other people sharing this file. They will be " +
                        "able to see your IP address, and you will upload to them while it plays.",
                )
            },
            confirmButton = { TextButton(onClick = onMagnetConfirmed) { Text("Download and play") } },
            dismissButton = { TextButton(onClick = onMagnetDismissed) { Text("Cancel") } },
        )
    }

    // One snackbar host for the whole app: feature ViewModels announce confirmations through
    // UiMessages without knowing which screen is on top. Errors stay inline with a Retry instead.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        UiMessages.setSink { message ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
        onDispose { UiMessages.setSink(null) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(ROUTE_PLAY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_PLAY,
            // consumeWindowInsets: this padding already applies the system-bar insets, so inner
            // Scaffolds/TopAppBars must not re-apply them — without it every screen with its own
            // top bar gets a status-bar-height empty band above the bar.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            // Subtle forward/back motion instead of the default hard cross-fade: pushes slide in
            // from the right, pops slide back out. Tab switches read as pushes too, which is
            // acceptable — a per-destination split isn't worth the ceremony here.
            enterTransition = { slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut() },
        ) {
            composable(ROUTE_PLAY) {
                PlayScreen(
                    onPlayVideo = { url -> navController.navigate("player?videoUrl=${Uri.encode(url)}") },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                    onMediaClick = { sourceId, mediaUrl, title, coverUrl ->
                        navController.navigateToDetails(sourceId, mediaUrl, title, coverUrl)
                    },
                    onAniListClick = { mediaId, _ -> navController.navigate("anilist/$mediaId") },
                    onAniListSearch = { navController.navigate(ROUTE_ANILIST_SEARCH) },
                    onSeeSchedule = { navController.navigate(ROUTE_AIRING_SCHEDULE) },
                )
            }
            composable(ROUTE_CATALOG) {
                CatalogScreen(
                    onMediaClick = { sourceId, mediaUrl, title, coverUrl ->
                        navController.navigateToDetails(sourceId, mediaUrl, title, coverUrl)
                    },
                    onManageSourcesClick = { navController.navigate(ROUTE_SOURCES) },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                )
            }
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    onMediaClick = { sourceId, mediaUrl, title, coverUrl ->
                        navController.navigateToDetails(sourceId, mediaUrl, title, coverUrl)
                    },
                    onPlayDirect = { url -> navController.navigate("player?videoUrl=${Uri.encode(url)}") },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onSourcesClick = { navController.navigate(ROUTE_SOURCES) },
                    onTrackingClick = { navController.navigate(ROUTE_TRACKING_SETTINGS) },
                    onStremioAccountClick = { navController.navigate(ROUTE_STREMIO_ACCOUNT) },
                )
            }
            composable(ROUTE_SOURCES) {
                SourcesScreen(
                    onBack = { navController.popBackStack() },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                    onBrowseInstallableSources = { navController.navigate(ROUTE_BROWSE_SOURCE_CATALOG) },
                    onManageAddons = { navController.navigate(ROUTE_MANAGE_STREMIO) },
                    onAnymexExtensions = { navController.navigate(ROUTE_ANYMEX_EXTENSIONS) },
                    onCustomSources = { navController.navigate(ROUTE_MANAGE_SOURCES) },
                )
            }
            composable(ROUTE_MANAGE_SOURCES) {
                ManageSourcesScreen(
                    onBrowseCatalogClick = { navController.navigate(ROUTE_BROWSE_SOURCE_CATALOG) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_BROWSE_SOURCE_CATALOG) {
                BrowseSourceCatalogScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_ANYMEX_EXTENSIONS) {
                MangayomiExtensionsScreen(
                    onBack = { navController.popBackStack() },
                    onConfigure = { sourceId -> navController.navigate("anymex-extension-prefs/$sourceId") },
                )
            }
            composable(
                ROUTE_ANYMEX_EXTENSION_PREFS,
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
            ) {
                MangayomiPreferencesScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_TRACKING_SETTINGS) {
                TrackingSettingsScreen(
                    onBack = { navController.popBackStack() },
                    pendingOAuthToken = pendingAniListToken,
                    pendingOAuthState = pendingAniListState,
                    onPendingOAuthTokenConsumed = onPendingAniListTokenConsumed,
                )
            }
            composable(
                ROUTE_MANAGE_STREMIO_PATTERN,
                arguments = listOf(
                    navArgument("installUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                // Navigation Compose already URL-decodes query-string arguments when populating
                // this Bundle — decoding again here would corrupt any %-encoded characters in
                // the manifest URL itself (unlike the path-segment args elsewhere in this file).
                val installUrl = entry.arguments?.getString("installUrl").orEmpty().ifEmpty { null }
                ManageStremioSourcesScreen(
                    onBack = { navController.popBackStack() },
                    prefillInstallUrl = installUrl,
                    onBrowseAddonsClick = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                )
            }
            composable(ROUTE_BROWSE_STREMIO) {
                BrowseStremioAddonsScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_STREMIO_ACCOUNT) {
                StremioAccountScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_DETAILS,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.LongType },
                    navArgument("mediaUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                    navArgument("title") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                    navArgument("coverUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val args = entry.arguments
                // Navigation Compose already URL-decodes query-string arguments when populating
                // this Bundle — decoding again here would corrupt any %-encoded characters in
                // the source URL/title themselves (unlike the path-segment args elsewhere in this file).
                MediaDetailsScreen(
                    sourceId = args?.getLong("sourceId") ?: 0L,
                    mediaUrl = args?.getString("mediaUrl").orEmpty(),
                    mediaTitle = args?.getString("title").orEmpty(),
                    // Blank rather than absent when the caller had no cover to pass; the screen
                    // treats it as "no seed" and falls back to whatever the source's own details
                    // carry, which is what every non-scripted source provides.
                    seedCoverUrl = args?.getString("coverUrl")?.takeIf { it.isNotBlank() },
                    // The one entry point where an installed source chose the url.
                    onPlayVideo = { videoUrl ->
                        navController.navigate("player?videoUrl=${Uri.encode(videoUrl)}&fromSource=true")
                    },
                    onOpenTracking = { navController.navigate(ROUTE_TRACKING_SETTINGS) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                ROUTE_ANILIST_DETAILS,
                arguments = listOf(navArgument("mediaId") { type = NavType.LongType }),
            ) {
                AniListDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAniList = { mediaId, _ -> navController.navigate("anilist/$mediaId") },
                    onWatch = { mediaId, title ->
                        navController.navigate("anilist-watch/$mediaId?title=${Uri.encode(title)}")
                    },
                    onOpenTracking = { navController.navigate(ROUTE_TRACKING_SETTINGS) },
                )
            }
            composable(
                ROUTE_ANILIST_WATCH,
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.LongType },
                    navArgument("title") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                ),
            ) {
                AniListWatchScreen(
                    onBack = { navController.popBackStack() },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                    onOpenSource = { sourceId, mediaUrl, title, coverUrl ->
                        // Replace the bridge in the back stack so returning from the source detail
                        // lands back on the AniList detail (and the bridge doesn't re-resolve the
                        // now-saved link into an immediate re-navigation loop).
                        // The picked search result's own poster travels with it. Only a target
                        // restored from an existing tracker link has none, because the link record
                        // stores no artwork — there it stays null and the source's own details fill
                        // the hero in, as they do everywhere else.
                        navController.navigate(
                            "details/$sourceId?mediaUrl=${Uri.encode(mediaUrl)}&title=${Uri.encode(title)}" +
                                "&coverUrl=${Uri.encode(coverUrl.orEmpty())}",
                        ) {
                            popUpTo(ROUTE_ANILIST_WATCH) { inclusive = true }
                        }
                    },
                )
            }
            composable(ROUTE_ANILIST_SEARCH) {
                AniListSearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAniList = { mediaId, _ -> navController.navigate("anilist/$mediaId") },
                )
            }
            composable(ROUTE_AIRING_SCHEDULE) {
                // Scoped to the Play destination, not this one. A bare hiltViewModel() here would
                // build a second AniListHomeViewModel, re-running discovery and re-fetching the
                // viewer's lists (which are not cached) just to render a schedule the Play tab has
                // already computed. Play is always beneath this screen on the back stack, since
                // this route is only reachable from it.
                val playEntry = remember(it) { navController.getBackStackEntry(ROUTE_PLAY) }
                AiringScheduleScreen(
                    onBack = { navController.popBackStack() },
                    onAniListClick = { mediaId, _ -> navController.navigate("anilist/$mediaId") },
                    viewModel = hiltViewModel(playEntry),
                )
            }
            composable(
                ROUTE_PLAYER,
                arguments = listOf(
                    navArgument("videoUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                    navArgument("fromSource") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                // Navigation Compose already URL-decodes query-string arguments — see the note above.
                val videoUrl = entry.arguments?.getString("videoUrl").orEmpty()
                // The player is always dark, whatever the rest of the app is set to. Its controls,
                // gesture HUDs and stats panel are drawn over video in white-on-black, and its
                // scrims lean on the theme's surface roles — in a light scheme that is white text
                // on a white wash. Watching a film with the lights on is also just not a thing
                // anyone asks for, so this is not a setting.
                OtakuStreamTheme(themeMode = ThemeMode.DARK) {
                    PlayerScreen(
                        videoUrl = videoUrl,
                        fromSource = entry.arguments?.getBoolean("fromSource") == true,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

// The cover travels with the destination rather than being re-fetched there.
//
// Not decoration: a scripted source's getMediaDetails() returns the MediaItem it was handed
// untouched, and the one built here has only a url and a title — so a detail screen reached from a
// scripted source had no artwork at all, showing the placeholder film icon under a title the
// catalog had just displayed over a poster. The catalog already holds the cover; passing it means
// the hero has an image on every source type, and the per-title accent has something to read.
private fun NavHostController.navigateToDetails(
    sourceId: Long,
    mediaUrl: String,
    title: String,
    coverUrl: String? = null,
) {
    navigate(
        "details/$sourceId?mediaUrl=${Uri.encode(mediaUrl)}&title=${Uri.encode(title)}" +
            "&coverUrl=${Uri.encode(coverUrl.orEmpty())}",
    )
}

@Composable
private fun SettingsScreen(
    onSourcesClick: () -> Unit,
    onTrackingClick: () -> Unit,
    onStremioAccountClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        )
        SectionHeader("Content")
        ListItem(
            headlineContent = { Text("Sources") },
            supportingContent = { Text("Find and manage your sources") },
            modifier = Modifier.clickable(onClick = onSourcesClick),
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
