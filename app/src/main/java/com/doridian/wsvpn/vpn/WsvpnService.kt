package com.doridian.wsvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.doridian.wsvpn.R
import com.doridian.wsvpn.data.AppFilterMode
import com.doridian.wsvpn.data.VpnProfile
import com.doridian.wsvpn.protocol.InitParameters
import com.doridian.wsvpn.protocol.WsvpnClient
import com.doridian.wsvpn.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

class WsvpnService : VpnService() {

    companion object {
        private const val TAG = "WsvpnService"
        private const val CHANNEL_ID = "wsvpn_vpn"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.doridian.wsvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.doridian.wsvpn.DISCONNECT"

        @Volatile
        var currentState: VpnState = VpnState.Disconnected("")
            private set

        var stateListener: ((VpnState) -> Unit)? = null
    }

    sealed class VpnState {
        object Connecting : VpnState()
        data class Connected(val serverIp: String, val clientIp: String) : VpnState()
        data class Disconnected(val reason: String) : VpnState()
        data class Error(val message: String) : VpnState()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var wsvpnClient: WsvpnClient? = null
    private var tunReadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var profile: VpnProfile? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverUrl = intent.getStringExtra("server_url") ?: ""
                val username = intent.getStringExtra("username") ?: ""
                val password = intent.getStringExtra("password") ?: ""
                val insecureTls = intent.getBooleanExtra("insecure_tls", false)
                val autoReconnect = intent.getBooleanExtra("auto_reconnect", true)
                val filterMode = intent.getStringExtra("app_filter_mode") ?: "ALL"
                val filteredApps = intent.getStringArrayExtra("filtered_apps")?.toSet() ?: emptySet()

                profile = VpnProfile(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    insecureTls = insecureTls,
                    autoReconnect = autoReconnect,
                    appFilterMode = AppFilterMode.valueOf(filterMode),
                    filteredApps = filteredApps
                )

                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                startVpn()
            }
            ACTION_DISCONNECT -> {
                stopVpn("User disconnected")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn("Service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        val prof = profile ?: return

        updateState(VpnState.Connecting)

        val config = WsvpnClient.WsvpnConfig(
            serverUrl = prof.serverUrl,
            username = prof.username,
            password = prof.password,
            insecureTls = prof.insecureTls
        )

        wsvpnClient = WsvpnClient(config, object : WsvpnClient.WsvpnListener {
            override fun onInitReceived(params: InitParameters) {
                scope.launch {
                    try {
                        setupTunInterface(params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to setup TUN", e)
                        updateState(VpnState.Error("Failed to setup TUN: ${e.message}"))
                        stopVpn("TUN setup failed")
                    }
                }
            }

            override fun onRouteAdded(route: String) {
                Log.i(TAG, "Route added (applied at TUN setup): $route")
            }

            override fun onMtuChanged(mtu: Int) {
                Log.i(TAG, "MTU changed to $mtu (requires reconnect to apply)")
            }

            override fun onDataPacket(packet: ByteArray) {
                try {
                    val output = vpnInterface?.fileDescriptor?.let { FileOutputStream(it) }
                    output?.write(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write to TUN", e)
                }
            }

            override fun onConnected() {
                Log.i(TAG, "WebSocket connected, waiting for init...")
            }

            override fun onDisconnected(reason: String) {
                val wasError = currentState is VpnState.Error
                updateState(VpnState.Disconnected(reason))
                val prof = profile
                if (prof?.autoReconnect == true && !wasError) {
                    scope.launch {
                        delay(3000)
                        if (currentState is VpnState.Disconnected) {
                            Log.i(TAG, "Auto-reconnecting...")
                            startVpn()
                        }
                    }
                } else {
                    stopSelf()
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "WsvpnClient error: $error")
                updateState(VpnState.Error(error))
            }
        })

        wsvpnClient?.connect()
    }

    private fun setupTunInterface(params: InitParameters) {
        // Parse IP address and prefix length
        val parts = params.ipAddress.split("/")
        val clientIp = parts[0]
        val prefixLength = parts.getOrNull(1)?.toIntOrNull() ?: 24

        // Calculate server IP (first usable IP in subnet)
        val ipBytes = InetAddress.getByName(clientIp).address
        val serverIpBytes = ipBytes.copyOf()
        serverIpBytes[3] = (serverIpBytes[3].toInt() and 0xFF xor 1).toByte()
        // Simple heuristic: if client is .2, server is .1; if client is .1, server is .2
        if (ipBytes[3].toInt() and 0xFF > 1) {
            serverIpBytes[3] = 1
        } else {
            serverIpBytes[3] = 2
        }
        val serverIp = InetAddress.getByAddress(serverIpBytes).hostAddress ?: "10.0.0.1"

        val builder = Builder()
            .setSession("WSVPN")
            .setMtu(params.mtu)
            .addAddress(clientIp, prefixLength)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addRoute("0.0.0.0", 0)

        // Apply per-app filtering
        val prof = profile
        if (prof != null) {
            when (prof.appFilterMode) {
                AppFilterMode.ALLOWED -> {
                    for (pkg in prof.filteredApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add allowed app: $pkg", e)
                        }
                    }
                }
                AppFilterMode.DISALLOWED -> {
                    for (pkg in prof.filteredApps) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to add disallowed app: $pkg", e)
                        }
                    }
                }
                AppFilterMode.ALL -> { /* no filtering */ }
            }
        }

        // Always exclude ourselves to avoid routing loops
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("VPN interface creation failed - permission denied?")

        // Start reading from TUN
        startTunReader()

        updateState(VpnState.Connected(serverIp, clientIp))
        updateNotification("Connected: $clientIp")

        Log.i(TAG, "TUN interface established: client=$clientIp server=$serverIp mtu=${params.mtu}")
    }

    private fun startTunReader() {
        tunReadJob?.cancel()
        tunReadJob = scope.launch {
            val fd = vpnInterface?.fileDescriptor ?: return@launch
            val input = FileInputStream(fd)
            val buffer = ByteArray(32767)

            try {
                while (isActive) {
                    val length = input.read(buffer)
                    if (length > 0) {
                        val packet = buffer.copyOf(length)
                        wsvpnClient?.sendDataPacket(packet)
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "TUN read error", e)
                }
            }
        }
    }

    private fun stopVpn(reason: String) {
        tunReadJob?.cancel()
        tunReadJob = null

        wsvpnClient?.disconnect()
        wsvpnClient = null

        vpnInterface?.close()
        vpnInterface = null

        profile = null
        updateState(VpnState.Disconnected(reason))
    }

    private fun updateState(state: VpnState) {
        currentState = state
        stateListener?.invoke(state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("WSVPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
