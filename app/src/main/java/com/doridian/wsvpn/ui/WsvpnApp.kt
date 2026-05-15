package com.doridian.wsvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun WsvpnApp(
    viewModel: MainViewModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "servers"
    val showBottomBar = currentRoute in listOf("servers", "apps", "settings")

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.VpnKey, contentDescription = "Connect") },
                        label = { Text("Connect") },
                        selected = currentRoute == "servers",
                        onClick = { navController.navigate("servers") { popUpTo("servers") { inclusive = true } } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                        label = { Text("Apps") },
                        selected = currentRoute == "apps",
                        onClick = { navController.navigate("apps") { popUpTo("servers") } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { popUpTo("servers") } }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "servers",
            modifier = Modifier.padding(padding)
        ) {
            composable("servers") {
                ServerListScreen(
                    viewModel = viewModel,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onEditServer = { id -> navController.navigate("server/edit/$id") },
                    onAddServer = { navController.navigate("server/edit/new") }
                )
            }
            composable(
                route = "server/edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: "new"
                EditServerScreen(
                    viewModel = viewModel,
                    serverId = id,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("apps") {
                AppFilterScreen(viewModel = viewModel)
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
