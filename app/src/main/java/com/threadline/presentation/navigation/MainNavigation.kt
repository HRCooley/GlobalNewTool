package com.threadline.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.threadline.presentation.board.BoardsScreen
import com.threadline.presentation.feed.FeedScreen
import com.threadline.presentation.map.MapScreen
import com.threadline.presentation.settings.SettingsScreen
import com.threadline.presentation.thread.ThreadsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Threadline") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            if (currentRoute != "settings") {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onItemSelected = { item ->
                        navController.navigate(item.route) {
                            popUpTo(BottomNavItem.Feed.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Feed.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(BottomNavItem.Feed.route) { FeedScreen() }
            composable(BottomNavItem.Threads.route) { ThreadsScreen() }
            composable(BottomNavItem.Board.route) { BoardsScreen() }
            composable(BottomNavItem.Map.route) { MapScreen() }
            composable("settings") { SettingsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
