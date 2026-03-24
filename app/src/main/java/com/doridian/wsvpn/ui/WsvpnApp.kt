package com.doridian.wsvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun WsvpnApp(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "connect"

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = "Connect") },
                    label = { Text("Connect") },
                    selected = currentRoute == "connect",
                    onClick = { navController.navigate("connect") { popUpTo("connect") { inclusive = true } } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                    label = { Text("Apps") },
                    selected = currentRoute == "apps",
                    onClick = {
                        viewModel.loadInstalledApps()
                        navController.navigate("apps") { popUpTo("connect") }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentRoute == "settings",
                    onClick = { navController.navigate("settings") { popUpTo("connect") } }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "connect",
            modifier = Modifier.padding(padding)
        ) {
            composable("connect") {
                ConnectScreen(viewModel = viewModel, onConnect = onConnect, onDisconnect = onDisconnect)
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
