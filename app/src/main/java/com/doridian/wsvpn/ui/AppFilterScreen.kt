package com.doridian.wsvpn.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.doridian.wsvpn.data.AppFilterMode
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(viewModel: MainViewModel) {
    val profile by viewModel.profile.collectAsState()
    val appList by viewModel.appList.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    val filteredApps = remember(
        appList.installedApps,
        appList.searchQuery,
        appList.showSystemApps,
        appList.showSelectedOnly,
        profile.filteredApps
    ) {
        appList.installedApps.filter { app ->
            val matchesSearch = appList.searchQuery.isEmpty() ||
                    app.label.contains(appList.searchQuery, ignoreCase = true) ||
                    app.packageName.contains(appList.searchQuery, ignoreCase = true)
            val matchesSystem = appList.showSystemApps || !app.isSystemApp
            val matchesSelected = !appList.showSelectedOnly ||
                    profile.filteredApps.contains(app.packageName)
            matchesSearch && matchesSystem && matchesSelected
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
                        selected = profile.appFilterMode == AppFilterMode.ALL,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.ALL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("All") }
                    SegmentedButton(
                        selected = profile.appFilterMode == AppFilterMode.ALLOWED,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.ALLOWED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("Only") }
                    SegmentedButton(
                        selected = profile.appFilterMode == AppFilterMode.DISALLOWED,
                        onClick = { viewModel.updateAppFilterMode(AppFilterMode.DISALLOWED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("Except") }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (profile.appFilterMode) {
                        AppFilterMode.ALL -> "All apps use VPN"
                        AppFilterMode.ALLOWED -> "Only selected apps use VPN"
                        AppFilterMode.DISALLOWED -> "All apps except selected use VPN"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (profile.appFilterMode != AppFilterMode.ALL) {
            // Search bar
            OutlinedTextField(
                value = appList.searchQuery,
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
                            tint = if (appList.showSystemApps) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            FilterChip(
                selected = appList.showSelectedOnly,
                onClick = { viewModel.toggleShowSelectedOnly() },
                enabled = profile.filteredApps.isNotEmpty(),
                label = {
                    Text(
                        "${profile.filteredApps.size} selected" +
                                if (!appList.showSystemApps) " (user apps only)" else ""
                    )
                },
                leadingIcon = if (appList.showSelectedOnly) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else null,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (appList.isLoadingApps && filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                if (appList.showSelectedOnly && filteredApps.isEmpty() && !appList.isLoadingApps) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No selected apps match the current filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = profile.filteredApps.contains(app.packageName)

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
                            AppIcon(
                                packageName = app.packageName,
                                modifier = Modifier.size(40.dp)
                            )
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
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Load on demand for the row currently scrolled into view; LazyColumn keeps the
    // composition around its window, so the icon stays cached while visible and is
    // dropped (along with its Drawable) once the row scrolls off-screen.
    val icon by produceState<Drawable?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
        }
    }
    val drawable = icon
    if (drawable != null) {
        Image(
            painter = rememberDrawablePainter(drawable),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Icon(
            Icons.Default.Apps,
            contentDescription = null,
            modifier = modifier
        )
    }
}
