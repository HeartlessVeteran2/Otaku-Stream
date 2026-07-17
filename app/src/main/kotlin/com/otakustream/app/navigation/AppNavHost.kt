package com.otakustream.app.navigation

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import com.otakustream.feature.sources.ui.CatalogScreen
import com.otakustream.feature.sources.ui.ManageSourcesScreen
import com.otakustream.feature.sources.ui.ManageStremioSourcesScreen
import com.otakustream.feature.sources.ui.MediaDetailsScreen
import com.otakustream.feature.tracking.TrackingSettingsScreen

private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_MANAGE_SOURCES = "manage-sources"
private const val ROUTE_TRACKING_SETTINGS = "tracking-settings"
private const val ROUTE_MANAGE_STREMIO = "manage-stremio"
private const val ROUTE_MANAGE_STREMIO_PATTERN = "manage-stremio?installUrl={installUrl}"
private const val ROUTE_DETAILS = "details/{sourceId}?mediaUrl={mediaUrl}&title={title}"
private const val ROUTE_PLAYER = "player?videoUrl={videoUrl}"

private data class BottomTab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val bottomTabs = listOf(
    BottomTab(ROUTE_CATALOG, "Home") { Icon(Icons.Filled.Home, contentDescription = null) },
    BottomTab(ROUTE_LIBRARY, "Library") { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
    BottomTab(ROUTE_SETTINGS, "Settings") { Icon(Icons.Filled.Settings, contentDescription = null) },
)

@Composable
fun AppNavHost(
    pendingStremioInstallUrl: String? = null,
    onPendingStremioInstallUrlConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == ROUTE_CATALOG || currentRoute == ROUTE_LIBRARY || currentRoute == ROUTE_SETTINGS

    LaunchedEffect(pendingStremioInstallUrl) {
        pendingStremioInstallUrl?.let { url ->
            navController.navigate("manage-stremio?installUrl=${Uri.encode(url)}")
            onPendingStremioInstallUrlConsumed()
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
                                    popUpTo(ROUTE_CATALOG) { saveState = true }
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
            startDestination = ROUTE_CATALOG,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_CATALOG) {
                CatalogScreen(
                    onMediaClick = { sourceId, mediaUrl, title -> navController.navigateToDetails(sourceId, mediaUrl, title) },
                    onManageSourcesClick = { navController.navigate(ROUTE_MANAGE_SOURCES) },
                )
            }
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    onMediaClick = { sourceId, mediaUrl, title -> navController.navigateToDetails(sourceId, mediaUrl, title) },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onManageSourcesClick = { navController.navigate(ROUTE_MANAGE_SOURCES) },
                    onTrackingClick = { navController.navigate(ROUTE_TRACKING_SETTINGS) },
                    onManageStremioClick = { navController.navigate(ROUTE_MANAGE_STREMIO) },
                )
            }
            composable(ROUTE_MANAGE_SOURCES) {
                ManageSourcesScreen()
            }
            composable(ROUTE_TRACKING_SETTINGS) {
                TrackingSettingsScreen()
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
                val installUrl = Uri.decode(entry.arguments?.getString("installUrl").orEmpty()).ifEmpty { null }
                ManageStremioSourcesScreen(prefillInstallUrl = installUrl)
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
                MediaDetailsScreen(
                    sourceId = args?.getLong("sourceId") ?: 0L,
                    mediaUrl = Uri.decode(args?.getString("mediaUrl").orEmpty()),
                    mediaTitle = Uri.decode(args?.getString("title").orEmpty()),
                    onPlayVideo = { videoUrl -> navController.navigate("player?videoUrl=${Uri.encode(videoUrl)}") },
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
                val videoUrl = Uri.decode(entry.arguments?.getString("videoUrl").orEmpty())
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("Manage sources") },
            supportingContent = { Text("Add or remove scripted video sources") },
            modifier = Modifier.clickable(onClick = onManageSourcesClick),
        )
        ListItem(
            headlineContent = { Text("AniList tracking") },
            supportingContent = { Text("Sync watch progress to your AniList account") },
            modifier = Modifier.clickable(onClick = onTrackingClick),
        )
        ListItem(
            headlineContent = { Text("Stremio addons") },
            supportingContent = { Text("Add addons and configure a streaming server") },
            modifier = Modifier.clickable(onClick = onManageStremioClick),
        )
    }
}
