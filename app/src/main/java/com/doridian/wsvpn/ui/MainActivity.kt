package com.doridian.wsvpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.doridian.wsvpn.ui.theme.WsvpnTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingConnectId: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingConnectId
        pendingConnectId = null
        if (result.resultCode == RESULT_OK && id != null) {
            viewModel.connectServer(id)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            WsvpnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WsvpnApp(
                        viewModel = viewModel,
                        onConnect = { id -> connectVpn(id) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }
        }
    }

    private fun connectVpn(serverId: String) {
        val prepareIntent = viewModel.getVpnPrepareIntent()
        if (prepareIntent != null) {
            pendingConnectId = serverId
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.connectServer(serverId)
        }
    }
}
