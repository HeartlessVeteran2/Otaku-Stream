package com.otakustream.app.navigation

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.otakustream.core.player.ui.PlayerScreen
import com.otakustream.feature.library.LibraryScreen
import com.otakustream.feature.sources.ui.AniListDetailScreen
import com.otakustream.feature.sources.ui.AniListSearchScreen
import com.otakustream.feature.sources.ui.AniListWatchScreen
import com.otakustream.feature.sources.ui.BrowseSourceCatalogScreen
import com.otakustream.feature.sources.ui.BrowseStremioAddonsScreen
import com.otakustream.feature.sources.ui.CatalogScreen
import com.otakustream.feature.sources.ui.MangayomiExtensionsScreen
import com.otakustream.feature.sources.ui.MangayomiPreferencesScreen
import com.otakustream.feature.sources.ui.ManageSourcesScreen
import com.otakustream.feature.sources.ui.ManageStremioSourcesScreen
import com.otakustream.feature.sources.ui.MediaDetailsScreen
import com.otakustream.feature.tracking.TrackingSettingsScreen

private const val ROUTE_PLAY = "play"
private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_MANAGE_SOURCES = "manage-sources"
private const val ROUTE_TRACKING_SETTINGS = "tracking-settings"
private const val ROUTE_MANAGE_STREMIO = "manage-stremio"
private const val ROUTE_MANAGE_STREMIO_PATTERN = "manage-stremio?installUrl={installUrl}"
private const val ROUTE_BROWSE_STREMIO = "browse-stremio"
private const val ROUTE_BROWSE_SOURCE_CATALOG = "browse-source-catalog"
private const val ROUTE_ANYMEX_EXTENSIONS = "anymex-extensions"
private const val ROUTE_ANYMEX_EXTENSION_PREFS = "anymex-extension-prefs/{sourceId}"
private const val ROUTE_DETAILS = "details/{sourceId}?mediaUrl={mediaUrl}&title={title}"
private const val ROUTE_ANILIST_DETAILS = "anilist/{mediaId}"
private const val ROUTE_ANILIST_WATCH = "anilist-watch/{mediaId}?title={title}"
private const val ROUTE_ANILIST_SEARCH = "anilist-search"
private const val ROUTE_PLAYER = "player?videoUrl={videoUrl}"

private data class BottomTab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val bottomTabs = listOf(
    BottomTab(ROUTE_PLAY, "Play") { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
    BottomTab(ROUTE_CATALOG, "Catalog") { Icon(Icons.Filled.Home, contentDescription = null) },
    BottomTab(ROUTE_LIBRARY, "Library") { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
    BottomTab(ROUTE_SETTINGS, "Settings") { Icon(Icons.Filled.Settings, contentDescription = null) },
)

@Composable
fun AppNavHost(
    pendingStremioInstallUrl: String? = null,
    onPendingStremioInstallUrlConsumed: () -> Unit = {},
    pendingPlayUrl: String? = null,
    onPendingPlayUrlConsumed: () -> Unit = {},
    pendingAniListToken: String? = null,
    onPendingAniListTokenConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
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

    Scaffold(
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
                            icon = tab.icon,
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
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_PLAY) {
                PlayScreen(
                    onPlayVideo = { url -> navController.navigate("player?videoUrl=${Uri.encode(url)}") },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                    onMediaClick = { sourceId, mediaUrl, title -> navController.navigateToDetails(sourceId, mediaUrl, title) },
                    onAniListClick = { mediaId, _ -> navController.navigate("anilist/$mediaId") },
                    onAniListSearch = { navController.navigate(ROUTE_ANILIST_SEARCH) },
                )
            }
            composable(ROUTE_CATALOG) {
                CatalogScreen(
                    onMediaClick = { sourceId, mediaUrl, title -> navController.navigateToDetails(sourceId, mediaUrl, title) },
                    onManageSourcesClick = { navController.navigate(ROUTE_MANAGE_SOURCES) },
                    onBrowseAddons = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                )
            }
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    onMediaClick = { sourceId, mediaUrl, title -> navController.navigateToDetails(sourceId, mediaUrl, title) },
                    onPlayDirect = { url -> navController.navigate("player?videoUrl=${Uri.encode(url)}") },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onManageSourcesClick = { navController.navigate(ROUTE_MANAGE_SOURCES) },
                    onTrackingClick = { navController.navigate(ROUTE_TRACKING_SETTINGS) },
                    onManageStremioClick = { navController.navigate(ROUTE_MANAGE_STREMIO) },
                    onAnymexExtensionsClick = { navController.navigate(ROUTE_ANYMEX_EXTENSIONS) },
                )
            }
            composable(ROUTE_MANAGE_SOURCES) {
                ManageSourcesScreen(
                    onBrowseCatalogClick = { navController.navigate(ROUTE_BROWSE_SOURCE_CATALOG) },
                )
            }
            composable(ROUTE_BROWSE_SOURCE_CATALOG) {
                BrowseSourceCatalogScreen()
            }
            composable(ROUTE_ANYMEX_EXTENSIONS) {
                MangayomiExtensionsScreen(
                    onConfigure = { sourceId -> navController.navigate("anymex-extension-prefs/$sourceId") },
                )
            }
            composable(
                ROUTE_ANYMEX_EXTENSION_PREFS,
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
            ) {
                MangayomiPreferencesScreen()
            }
            composable(ROUTE_TRACKING_SETTINGS) {
                TrackingSettingsScreen(
                    pendingOAuthToken = pendingAniListToken,
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
                    prefillInstallUrl = installUrl,
                    onBrowseAddonsClick = { navController.navigate(ROUTE_BROWSE_STREMIO) },
                )
            }
            composable(ROUTE_BROWSE_STREMIO) {
                BrowseStremioAddonsScreen()
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
                    onPlayVideo = { videoUrl -> navController.navigate("player?videoUrl=${Uri.encode(videoUrl)}") },
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
                    onOpenSource = { sourceId, mediaUrl, title ->
                        // Replace the bridge in the back stack so returning from the source detail
                        // lands back on the AniList detail (and the bridge doesn't re-resolve the
                        // now-saved link into an immediate re-navigation loop).
                        navController.navigate(
                            "details/$sourceId?mediaUrl=${Uri.encode(mediaUrl)}&title=${Uri.encode(title)}",
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
            composable(
                ROUTE_PLAYER,
                arguments = listOf(
                    navArgument("videoUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                // Navigation Compose already URL-decodes query-string arguments — see the note above.
                val videoUrl = entry.arguments?.getString("videoUrl").orEmpty()
                PlayerScreen(videoUrl = videoUrl)
            }
        }
    }
}

private fun NavHostController.navigateToDetails(sourceId: Long, mediaUrl: String, title: String) {
    navigate("details/$sourceId?mediaUrl=${Uri.encode(mediaUrl)}&title=${Uri.encode(title)}")
}

@Composable
private fun SettingsScreen(
    onManageSourcesClick: () -> Unit,
    onTrackingClick: () -> Unit,
    onManageStremioClick: () -> Unit,
    onAnymexExtensionsClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("Add-ons") },
            supportingContent = { Text("Install add-ons that fill your catalog") },
            modifier = Modifier.clickable(onClick = onManageStremioClick),
        )
        ListItem(
            headlineContent = { Text("AnymeX extensions") },
            supportingContent = { Text("Install anime extensions from an AnymeX/Mangayomi repository") },
            modifier = Modifier.clickable(onClick = onAnymexExtensionsClick),
        )
        ListItem(
            headlineContent = { Text("AniList tracking") },
            supportingContent = { Text("Sync watch progress to your AniList account") },
            modifier = Modifier.clickable(onClick = onTrackingClick),
        )
        ListItem(
            headlineContent = { Text("Custom sources") },
            supportingContent = { Text("Advanced: add script-based video sources") },
            modifier = Modifier.clickable(onClick = onManageSourcesClick),
        )
    }
}
