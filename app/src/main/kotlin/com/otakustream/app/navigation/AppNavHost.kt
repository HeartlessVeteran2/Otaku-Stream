package com.otakustream.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.otakustream.core.player.ui.PlayerScreen
import com.otakustream.feature.sources.ui.CatalogScreen
import com.otakustream.feature.sources.ui.ManageSourcesScreen
import com.otakustream.feature.sources.ui.MediaDetailsScreen

private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_MANAGE_SOURCES = "manage-sources"
private const val ROUTE_DETAILS = "details/{sourceId}?mediaUrl={mediaUrl}&title={title}"
private const val ROUTE_PLAYER = "player?videoUrl={videoUrl}"

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_CATALOG) {
        composable(ROUTE_CATALOG) {
            CatalogScreen(
                onMediaClick = { sourceId, mediaUrl, title ->
                    navController.navigate("details/$sourceId?mediaUrl=${Uri.encode(mediaUrl)}&title=${Uri.encode(title)}")
                },
                onManageSourcesClick = { navController.navigate(ROUTE_MANAGE_SOURCES) },
            )
        }
        composable(ROUTE_MANAGE_SOURCES) {
            ManageSourcesScreen()
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
        ) { backStackEntry ->
            val args = backStackEntry.arguments
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
        ) { backStackEntry ->
            val videoUrl = Uri.decode(backStackEntry.arguments?.getString("videoUrl").orEmpty())
            PlayerScreen(videoUrl = videoUrl)
        }
    }
}
