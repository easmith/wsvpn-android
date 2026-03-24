package com.doridian.wsvpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.doridian.wsvpn.data.AppFilterMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    val filteredApps = remember(state.installedApps, state.appSearchQuery, state.showSystemApps) {
        state.installedApps.filter { app ->
            val matchesSearch = state.appSearchQuery.isEmpty() ||
                    app.label.contains(state.appSearchQuery, ignoreCase = true) ||
                    app.packageName.contains(state.appSearchQuery, ignoreCase = true)
            val matchesSystem = state.showSystemApps || !app.isSystemApp
            matchesSearch && matchesSystem
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter mode selector
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Per-App VPN",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.profile.appFilterMode == AppFilterMode.ALL,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.ALL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("All") }
                    SegmentedButton(
                        selected = state.profile.appFilterMode == AppFilterMode.ALLOWED,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.ALLOWED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("Only") }
                    SegmentedButton(
                        selected = state.profile.appFilterMode == AppFilterMode.DISALLOWED,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.DISALLOWED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("Except") }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (state.profile.appFilterMode) {
                        AppFilterMode.ALL -> "All apps use VPN"
                        AppFilterMode.ALLOWED -> "Only selected apps use VPN"
                        AppFilterMode.DISALLOWED -> "All apps except selected use VPN"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.profile.appFilterMode != AppFilterMode.ALL) {
            // Search bar
            OutlinedTextField(
                value = state.appSearchQuery,
                onValueChange = { viewModel.updateAppSearchQuery(it) },
                placeholder = { Text("Search apps...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { viewModel.toggleShowSystemApps() }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Toggle system apps",
                            tint = if (state.showSystemApps) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${state.profile.filteredApps.size} selected" +
                        if (!state.showSystemApps) " (user apps only)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = state.profile.filteredApps.contains(app.packageName)

                    ListItem(
                        headlineContent = { Text(app.label, maxLines = 1) },
                        supportingContent = {
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        },
                        leadingContent = {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon.toBitmap(40, 40).asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        },
                        trailingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleAppFilter(app.packageName) }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // Save settings when leaving
    DisposableEffect(Unit) {
        onDispose { viewModel.saveSettings() }
    }
}
