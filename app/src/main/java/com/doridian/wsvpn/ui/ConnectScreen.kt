package com.doridian.wsvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.doridian.wsvpn.vpn.WsvpnService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    val isConnected = state.vpnState is WsvpnService.VpnState.Connected
    val isConnecting = state.vpnState is WsvpnService.VpnState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WSVPN",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (state.vpnState) {
                    is WsvpnService.VpnState.Connected -> MaterialTheme.colorScheme.primaryContainer
                    is WsvpnService.VpnState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
                    is WsvpnService.VpnState.Error -> MaterialTheme.colorScheme.errorContainer
                    is WsvpnService.VpnState.Disconnected -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (state.vpnState) {
                        is WsvpnService.VpnState.Connected -> Icons.Default.CheckCircle
                        is WsvpnService.VpnState.Connecting -> Icons.Default.Sync
                        is WsvpnService.VpnState.Error -> Icons.Default.Error
                        is WsvpnService.VpnState.Disconnected -> Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (val s = state.vpnState) {
                            is WsvpnService.VpnState.Connected -> "Connected"
                            is WsvpnService.VpnState.Connecting -> "Connecting..."
                            is WsvpnService.VpnState.Error -> "Error"
                            is WsvpnService.VpnState.Disconnected -> "Disconnected"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    when (val s = state.vpnState) {
                        is WsvpnService.VpnState.Connected -> {
                            Text("IP: ${s.clientIp}", style = MaterialTheme.typography.bodySmall)
                        }
                        is WsvpnService.VpnState.Error -> {
                            Text(s.message, style = MaterialTheme.typography.bodySmall)
                        }
                        is WsvpnService.VpnState.Disconnected -> {
                            if (s.reason.isNotEmpty()) {
                                Text(s.reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Server URL
        OutlinedTextField(
            value = state.profile.serverUrl,
            onValueChange = { viewModel.updateServerUrl(it) },
            label = { Text("Server URL") },
            placeholder = { Text("wss://example.com:9000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnected && !isConnecting,
            leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Username
        OutlinedTextField(
            value = state.profile.username,
            onValueChange = { viewModel.updateUsername(it) },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnected && !isConnecting,
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Password
        OutlinedTextField(
            value = state.profile.password,
            onValueChange = { viewModel.updatePassword(it) },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnected && !isConnecting,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Connect/Disconnect button
        Button(
            onClick = {
                if (isConnected || isConnecting) {
                    onDisconnect()
                } else {
                    onConnect()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = (isConnected || isConnecting) || state.profile.serverUrl.isNotBlank(),
            colors = if (isConnected || isConnecting) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Icon(
                imageVector = if (isConnected || isConnecting) Icons.Default.LinkOff else Icons.Default.Link,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isConnecting -> "Cancel"
                    isConnected -> "Disconnect"
                    else -> "Connect"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
