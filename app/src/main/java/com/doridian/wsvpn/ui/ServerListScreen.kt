package com.doridian.wsvpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doridian.wsvpn.data.VpnServer
import com.doridian.wsvpn.vpn.WsvpnService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: MainViewModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onEditServer: (String) -> Unit,
    onAddServer: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddServer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add server") }
            )
        }
    ) { padding ->
        if (state.servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No servers yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap Add server to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        isActive = server.id == state.activeServerId,
                        vpnState = state.vpnState,
                        onConnect = { onConnect(server.id) },
                        onDisconnect = onDisconnect,
                        onEdit = { onEditServer(server.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: VpnServer,
    isActive: Boolean,
    vpnState: WsvpnService.VpnState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit
) {
    val activeAndLive = isActive && vpnState !is WsvpnService.VpnState.Disconnected &&
            vpnState !is WsvpnService.VpnState.Error

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = if (activeAndLive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName(server),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    if (server.serverUrl.isNotBlank()) {
                        Text(
                            text = server.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                VpnStatusLine(vpnState)
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (activeAndLive) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (vpnState) {
                            is WsvpnService.VpnState.Connecting -> "Cancel"
                            else -> "Disconnect"
                        }
                    )
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = server.serverUrl.isNotBlank()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun VpnStatusLine(state: WsvpnService.VpnState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (state) {
                is WsvpnService.VpnState.Connected -> Icons.Default.CheckCircle
                is WsvpnService.VpnState.Connecting -> Icons.Default.Sync
                is WsvpnService.VpnState.Reconnecting -> Icons.Default.Sync
                is WsvpnService.VpnState.Error -> Icons.Default.Error
                is WsvpnService.VpnState.Disconnected -> Icons.Default.CloudOff
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = when (state) {
                    is WsvpnService.VpnState.Connected -> "Connected"
                    is WsvpnService.VpnState.Connecting -> "Connecting..."
                    is WsvpnService.VpnState.Reconnecting -> "Reconnecting..."
                    is WsvpnService.VpnState.Error -> "Error"
                    is WsvpnService.VpnState.Disconnected ->
                        if (state.killSwitchActive) "Blocked by kill-switch" else "Disconnected"
                },
                style = MaterialTheme.typography.labelLarge
            )
            val detail = when (state) {
                is WsvpnService.VpnState.Connected -> "IP: ${state.clientIp}"
                is WsvpnService.VpnState.Reconnecting -> "Attempt ${state.attempt}"
                is WsvpnService.VpnState.Error -> state.message
                is WsvpnService.VpnState.Disconnected -> state.reason.ifBlank { null }
                else -> null
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

private fun displayName(server: VpnServer): String {
    if (server.name.isNotBlank()) return server.name
    val host = try {
        java.net.URI(server.serverUrl).host
    } catch (_: Exception) { null }
    if (!host.isNullOrBlank()) return host
    return server.serverUrl.ifBlank { "(unnamed)" }
}
