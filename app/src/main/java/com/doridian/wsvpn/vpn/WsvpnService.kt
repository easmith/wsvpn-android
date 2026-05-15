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
import com.doridian.wsvpn.data.VpnServer
import com.doridian.wsvpn.data.VpnSettingsRepository
import com.doridian.wsvpn.protocol.InitParameters
import com.doridian.wsvpn.protocol.WsvpnClient
import com.doridian.wsvpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class WsvpnService : VpnService() {

    companion object {
        private const val TAG = "WsvpnService"
        private const val CHANNEL_ID = "wsvpn_vpn"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.doridian.wsvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.doridian.wsvpn.DISCONNECT"

        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_INSECURE_TLS = "insecure_tls"
        const val EXTRA_AUTO_RECONNECT = "auto_reconnect"
        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_APP_FILTER_MODE = "app_filter_mode"
        const val EXTRA_FILTERED_APPS = "filtered_apps"

        @Volatile
        var currentState: VpnState = VpnState.Disconnected("")
            private set

        @Volatile
        var activeServerId: String? = null
            private set

        var stateListener: ((VpnState, String?) -> Unit)? = null
    }

    sealed class VpnState {
        object Connecting : VpnState()
        data class Connected(val serverIp: String, val clientIp: String) : VpnState()
        data class Reconnecting(val attempt: Int, val reason: String) : VpnState()
        data class Disconnected(val reason: String, val killSwitchActive: Boolean = false) : VpnState()
        data class Error(val message: String) : VpnState()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private var wsvpnClient: WsvpnClient? = null
    private var tunReadJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var profile: VpnProfile? = null
    private var server: VpnServer? = null
    private var reconnectAttempt = 0

    // Bumped on every session boundary (start / stop). Each WsvpnClient listener
    // captures the session it was created for and ignores callbacks that arrive
    // after the session has been replaced — guards against an old client's async
    // onDisconnected firing into a freshly-started new session.
    private val sessionId = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restart (e.g. Always-on VPN). Load the saved profile + selected
            // server from storage and connect — no intent extras to read from.
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
            scope.launch {
                val repo = VpnSettingsRepository(this@WsvpnService)
                val savedProfile = try { repo.profile.first() } catch (e: Exception) {
                    Log.e(TAG, "Failed to load profile", e); null
                }
                val savedId = try { repo.selectedServerId.first() } catch (e: Exception) {
                    Log.e(TAG, "Failed to load selected server id", e); null
                }
                val savedServer = savedId?.let {
                    try { repo.getServer(it) } catch (e: Exception) {
                        Log.e(TAG, "Failed to load server", e); null
                    }
                }
                if (savedProfile == null || savedServer == null || savedServer.serverUrl.isBlank()) {
                    Log.w(TAG, "Always-on VPN start with no usable saved configuration")
                    updateState(VpnState.Error("No saved VPN configuration"))
                    stopSelf()
                    return@launch
                }
                profile = savedProfile
                server = savedServer
                setActiveServerId(savedServer.id)
                startVpn()
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
                val insecureTls = intent.getBooleanExtra(EXTRA_INSECURE_TLS, false)
                val autoReconnect = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, true)
                val killSwitch = intent.getBooleanExtra(EXTRA_KILL_SWITCH, true)
                val filterMode = intent.getStringExtra(EXTRA_APP_FILTER_MODE) ?: "ALL"
                val filteredApps = intent.getStringArrayExtra(EXTRA_FILTERED_APPS)?.toSet() ?: emptySet()

                // If we are mid-flight (connected, connecting, reconnecting, or kill-
                // switch-blocking), tear it all down before starting the new server so
                // there's never overlap.
                if (wsvpnClient != null || vpnInterface != null || reconnectJob != null) {
                    stopVpn("Switching server")
                }

                profile = VpnProfile(
                    insecureTls = insecureTls,
                    autoReconnect = autoReconnect,
                    killSwitch = killSwitch,
                    appFilterMode = AppFilterMode.valueOf(filterMode),
                    filteredApps = filteredApps
                )
                server = VpnServer(
                    id = serverId,
                    serverUrl = serverUrl,
                    username = username,
                    password = password
                )
                setActiveServerId(serverId.ifBlank { null })

                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                startVpn()
            }
            ACTION_DISCONNECT -> {
                stopVpn("User disconnected")
                setActiveServerId(null)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn("Service destroyed")
        setActiveServerId(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        val srv = server ?: return
        val prof = profile ?: return

        val mySession = sessionId.incrementAndGet()

        updateState(VpnState.Connecting)

        val config = WsvpnClient.WsvpnConfig(
            serverUrl = srv.serverUrl,
            username = srv.username,
            password = srv.password,
            insecureTls = prof.insecureTls
        )

        wsvpnClient = WsvpnClient(config, vpnService = this, listener = object : WsvpnClient.WsvpnListener {
            override fun onInitReceived(params: InitParameters) {
                if (mySession != sessionId.get()) return
                scope.launch {
                    if (mySession != sessionId.get()) return@launch
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
                if (mySession != sessionId.get()) return
                try {
                    tunOutput?.write(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write to TUN", e)
                }
            }

            override fun onConnected() {
                Log.i(TAG, "WebSocket connected, waiting for init...")
            }

            override fun onDisconnected(reason: String) {
                if (mySession != sessionId.get()) {
                    Log.d(TAG, "Ignoring stale onDisconnected (session $mySession, current ${sessionId.get()}): $reason")
                    return
                }
                val wasError = currentState is VpnState.Error
                val prof = profile
                val killSwitch = prof?.killSwitch ?: false
                val shouldReconnect = prof?.autoReconnect == true && !wasError

                // With kill-switch: keep TUN up so apps see no connectivity instead of
                // leaking to the underlying network. Packets keep being read but are
                // dropped at sendDataPacket() because the WS is not initialized.
                // Without kill-switch, or on a fatal Error: tear down TUN so the system
                // VPN indicator and routing reflect reality.
                val keepTunUp = killSwitch && !wasError
                if (!keepTunUp) {
                    tunReadJob?.cancel()
                    tunReadJob = null
                    runCatching { tunOutput?.close() }
                    tunOutput = null
                    vpnInterface?.close()
                    vpnInterface = null
                }

                if (shouldReconnect) {
                    val attempt = reconnectAttempt
                    reconnectAttempt = attempt + 1
                    val delayMs = (3000L shl attempt.coerceAtMost(5)).coerceAtMost(60_000L)

                    if (killSwitch) {
                        updateState(VpnState.Reconnecting(attempt + 1, reason))
                        updateNotification("Reconnecting in ${delayMs / 1000}s (attempt ${attempt + 1}) — traffic blocked")
                    } else {
                        updateState(VpnState.Disconnected(reason))
                        updateNotification("Reconnecting in ${delayMs / 1000}s (attempt ${attempt + 1})...")
                    }
                    Log.i(TAG, "Scheduling reconnect in ${delayMs}ms (attempt ${attempt + 1}, killSwitch=$killSwitch)")
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(delayMs)
                        val st = currentState
                        if (st is VpnState.Disconnected || st is VpnState.Reconnecting) {
                            Log.i(TAG, "Auto-reconnecting...")
                            startVpn()
                        }
                    }
                } else if (killSwitch && !wasError) {
                    // Kill-switch on, no auto-reconnect: keep TUN up, block traffic,
                    // wait for user to manually reconnect or disconnect.
                    updateState(VpnState.Disconnected(reason, killSwitchActive = true))
                    updateNotification("Disconnected — traffic blocked by kill-switch")
                } else {
                    updateState(VpnState.Disconnected(reason))
                    stopSelf()
                }
            }

            override fun onError(error: String) {
                if (mySession != sessionId.get()) return
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

        // Parse server IP to exclude from VPN routes (avoid routing loop)
        val serverHost = try {
            java.net.URI(server?.serverUrl ?: "").host
        } catch (_: Exception) { null }
        val serverAddr = serverHost?.let {
            try { InetAddress.getByName(it) } catch (_: Exception) { null }
        }

        val builder = Builder()
            .setSession("WSVPN")
            .setMtu(params.mtu)
            .addAddress(clientIp, prefixLength)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")

        if (serverAddr != null) {
            // Add routes covering all IPs except the server IP
            for (route in getRoutesExcluding(serverAddr.address)) {
                builder.addRoute(InetAddress.getByAddress(route.first), route.second)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

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

        // Exclude ourselves to avoid routing loops (our tunnel socket must bypass VPN)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
            // Fails in ALLOWED mode (can't mix allowed/disallowed) — but in that mode
            // we're already excluded unless explicitly in the allowed list
        }

        // Close any stale TUN before establishing a new one (e.g., during auto-reconnect
        // setupTunInterface runs again with a fresh fd).
        runCatching { tunOutput?.close() }
        tunOutput = null
        vpnInterface?.close()
        vpnInterface = builder.establish()
            ?: throw IllegalStateException("VPN interface creation failed - permission denied?")
        tunOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

        // Now that VPN is established, protect the WebSocket socket
        wsvpnClient?.protectSocket()

        // Start reading from TUN
        startTunReader()

        reconnectAttempt = 0
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
                        // Check IP version: only forward IPv4 (server TUN is IPv4-only)
                        val ipVersion = (buffer[0].toInt() and 0xF0) ushr 4
                        if (ipVersion != 4) {
                            continue
                        }
                        wsvpnClient?.sendDataPacket(buffer, 0, length)
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
        // Invalidate the current session so any in-flight WsvpnClient callbacks
        // (which run on background threads after disconnect()) become no-ops
        // instead of mutating state that now belongs to a fresh session.
        sessionId.incrementAndGet()

        reconnectJob?.cancel()
        reconnectJob = null

        tunReadJob?.cancel()
        tunReadJob = null

        wsvpnClient?.disconnect()
        wsvpnClient = null

        runCatching { tunOutput?.close() }
        tunOutput = null
        vpnInterface?.close()
        vpnInterface = null

        profile = null
        server = null
        reconnectAttempt = 0
        // Don't overwrite Error state with a generic "Service destroyed" message
        if (currentState !is VpnState.Error) {
            updateState(VpnState.Disconnected(reason))
        }
    }

    private fun updateState(state: VpnState) {
        currentState = state
        stateListener?.invoke(state, activeServerId)
    }

    private fun setActiveServerId(id: String?) {
        if (activeServerId == id) return
        activeServerId = id
        stateListener?.invoke(currentState, id)
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

    /**
     * Generate CIDR routes covering all IPv4 addresses except the given one.
     * This prevents VPN routing loops by excluding the VPN server's IP.
     */
    private fun getRoutesExcluding(excludeIp: ByteArray): List<Pair<ByteArray, Int>> {
        val routes = mutableListOf<Pair<ByteArray, Int>>()
        val excludeBits = ipToInt(excludeIp)

        fun split(base: Int, prefix: Int) {
            if (prefix > 32) return
            // Check if the excluded IP falls within this subnet
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            if ((excludeBits and mask) != (base and mask)) {
                // Excluded IP is not in this subnet, add the whole subnet
                routes.add(Pair(intToIp(base), prefix))
                return
            }
            if (prefix == 32) {
                // This is the excluded IP itself, skip it
                return
            }
            // Split into two halves
            val nextPrefix = prefix + 1
            val bit = 1 shl (31 - prefix)
            split(base, nextPrefix)
            split(base or bit, nextPrefix)
        }

        split(0, 0)
        return routes
    }

    private fun ipToInt(ip: ByteArray): Int {
        return ((ip[0].toInt() and 0xFF) shl 24) or
                ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or
                (ip[3].toInt() and 0xFF)
    }

    private fun intToIp(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }
}
