package com.otakustream.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.otakustream.core.player.ui.PlayerScreen

private const val SAMPLE_HLS_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

private const val ROUTE_PLAYER = "player"

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_PLAYER) {
        composable(ROUTE_PLAYER) {
            PlayerScreen(videoUrl = SAMPLE_HLS_URL)
        }
    }
}
