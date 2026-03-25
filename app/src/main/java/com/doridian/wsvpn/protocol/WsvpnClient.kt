package com.doridian.wsvpn.protocol

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException

class WsvpnClient(
    private val config: WsvpnConfig,
    private val listener: WsvpnListener
) {
    companion object {
        private const val TAG = "WsvpnClient"
        const val PROTOCOL_VERSION = 12
        const val APP_VERSION = "wsvpn-android 1.0.0"
    }

    interface WsvpnListener {
        fun onInitReceived(params: InitParameters)
        fun onRouteAdded(route: String)
        fun onMtuChanged(mtu: Int)
        fun onDataPacket(packet: ByteArray)
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onError(error: String)
    }

    data class WsvpnConfig(
        val serverUrl: String,
        val username: String = "",
        val password: String = "",
        val insecureTls: Boolean = false
    )

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient
    private var fragmenter: PacketFragmenter? = null
    private var defragmenter: PacketDefragmenter? = null
    private var fragmentationEnabled = false
    private var negotiatedMtu = 1420
    private val pendingReplies = ConcurrentHashMap<String, CountDownLatch>()
    private val replyErrors = ConcurrentHashMap<String, String>()
    @Volatile private var connected = false
    @Volatile private var initialized = false

    init {
        val builder = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)

        if (config.insecureTls) {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        client = builder.build()
    }

    fun connect() {
        if (config.serverUrl.isBlank()) {
            listener.onError("Server URL is empty")
            return
        }
        if (!config.serverUrl.startsWith("ws://") && !config.serverUrl.startsWith("wss://")) {
            listener.onError("Server URL must start with ws:// or wss://")
            return
        }

        val requestBuilder = Request.Builder()
            .url(config.serverUrl)
            .header("User-Agent", APP_VERSION)
            .header("Supported-Command-Serializations", "json")

        if (config.username.isNotEmpty()) {
            val credentials = Credentials.basic(config.username, config.password)
            requestBuilder.header("Authorization", credentials)
        }

        val request = requestBuilder.build()
        webSocket = client.newWebSocket(request, WsvpnWebSocketListener())
    }

    fun disconnect() {
        connected = false
        initialized = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun sendDataPacket(packet: ByteArray) {
        if (!initialized) return
        val ws = webSocket ?: return

        if (fragmentationEnabled && fragmenter != null) {
            val fragments = fragmenter!!.fragment(packet)
            for (fragment in fragments) {
                ws.send(fragment.toByteString(0, fragment.size))
            }
        } else {
            ws.send(packet.toByteString(0, packet.size))
        }
    }

    private fun sendCommand(command: WsvpnCommand) {
        webSocket?.send(command.toJson())
    }

    private fun handleCommand(cmd: WsvpnCommand) {
        Log.d(TAG, "Received command: ${cmd.command} id=${cmd.id}")

        when (cmd.command) {
            "version" -> handleVersion(cmd)
            "init" -> handleInit(cmd)
            "add_route" -> handleAddRoute(cmd)
            "set_mtu" -> handleSetMtu(cmd)
            "reply" -> handleReply(cmd)
            "message" -> handleMessage(cmd)
            else -> {
                Log.w(TAG, "Unknown command: ${cmd.command}")
                sendCommand(WsvpnCommand.reply(cmd.id, "Unknown command: ${cmd.command}"))
            }
        }
    }

    private fun handleVersion(cmd: WsvpnCommand) {
        val params = VersionParameters.fromJsonObject(cmd.parameters)
        Log.i(TAG, "Server version: ${params.version}, protocol: ${params.protocolVersion}")

        if (params.protocolVersion != PROTOCOL_VERSION) {
            listener.onError("Protocol version mismatch: server=${params.protocolVersion}, client=$PROTOCOL_VERSION")
            disconnect()
            return
        }

        fragmentationEnabled = params.enabledFeatures.contains("fragmentation")

        // Reply to server's version
        sendCommand(WsvpnCommand.reply(cmd.id))
    }

    private fun handleInit(cmd: WsvpnCommand) {
        val params = InitParameters.fromJsonObject(cmd.parameters)
        Log.i(TAG, "Init: mode=${params.mode}, ip=${params.ipAddress}, mtu=${params.mtu}")

        if (params.mode != "TUN") {
            sendCommand(WsvpnCommand.reply(cmd.id, "Unsupported mode: ${params.mode}"))
            listener.onError("Unsupported mode: ${params.mode}. Only TUN is supported on Android.")
            disconnect()
            return
        }

        // Validate IP address format
        if (params.ipAddress.isBlank()) {
            sendCommand(WsvpnCommand.reply(cmd.id, "Empty IP address"))
            listener.onError("Server sent empty IP address")
            disconnect()
            return
        }
        val ipParts = params.ipAddress.split("/")
        if (ipParts.size != 2) {
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid IP address format"))
            listener.onError("Invalid IP address format: ${params.ipAddress} (expected CIDR notation)")
            disconnect()
            return
        }
        try {
            InetAddress.getByName(ipParts[0])
        } catch (e: Exception) {
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid IP address"))
            listener.onError("Invalid IP address: ${ipParts[0]}")
            disconnect()
            return
        }
        val prefixLen = ipParts[1].toIntOrNull()
        if (prefixLen == null || prefixLen < 0 || prefixLen > 32) {
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid prefix length"))
            listener.onError("Invalid prefix length: ${ipParts[1]}")
            disconnect()
            return
        }

        // Validate MTU
        if (params.mtu < 576 || params.mtu > 65535) {
            sendCommand(WsvpnCommand.reply(cmd.id, "MTU out of range"))
            listener.onError("MTU out of valid range (576-65535): ${params.mtu}")
            disconnect()
            return
        }

        negotiatedMtu = params.mtu

        if (fragmentationEnabled) {
            fragmenter = PacketFragmenter(negotiatedMtu + 80)
            defragmenter = PacketDefragmenter()
        }

        initialized = true
        listener.onInitReceived(params)

        // Reply to server's init
        sendCommand(WsvpnCommand.reply(cmd.id))
    }

    private fun handleAddRoute(cmd: WsvpnCommand) {
        val params = AddRouteParameters.fromJsonObject(cmd.parameters)
        Log.i(TAG, "Add route: ${params.route}")

        // Validate route format (CIDR)
        val routeParts = params.route.split("/")
        if (routeParts.size != 2) {
            Log.w(TAG, "Invalid route format, ignoring: ${params.route}")
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid route format"))
            return
        }
        try {
            InetAddress.getByName(routeParts[0])
        } catch (e: Exception) {
            Log.w(TAG, "Invalid route address, ignoring: ${params.route}")
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid route address"))
            return
        }
        val routePrefix = routeParts[1].toIntOrNull()
        if (routePrefix == null || routePrefix < 0 || routePrefix > 32) {
            Log.w(TAG, "Invalid route prefix, ignoring: ${params.route}")
            sendCommand(WsvpnCommand.reply(cmd.id, "Invalid route prefix"))
            return
        }

        listener.onRouteAdded(params.route)
        sendCommand(WsvpnCommand.reply(cmd.id))
    }

    private fun handleSetMtu(cmd: WsvpnCommand) {
        val params = SetMtuParameters.fromJsonObject(cmd.parameters)
        Log.i(TAG, "Set MTU: ${params.mtu}")

        if (params.mtu < 576 || params.mtu > 65535) {
            Log.w(TAG, "MTU out of range, ignoring: ${params.mtu}")
            sendCommand(WsvpnCommand.reply(cmd.id, "MTU out of range"))
            return
        }

        negotiatedMtu = params.mtu
        listener.onMtuChanged(params.mtu)
        sendCommand(WsvpnCommand.reply(cmd.id))
    }

    private fun handleReply(cmd: WsvpnCommand) {
        val error = cmd.parameters.get("error")?.asString ?: ""
        if (error.isNotEmpty()) {
            Log.e(TAG, "Reply error for ${cmd.id}: $error")
            replyErrors[cmd.id] = error
            // If the server rejects our version, disconnect
            if (!initialized) {
                listener.onError("Server rejected handshake: $error")
                disconnect()
                return
            }
        }
        pendingReplies[cmd.id]?.countDown()
    }

    private fun handleMessage(cmd: WsvpnCommand) {
        val message = cmd.parameters.get("message")?.asString ?: ""
        Log.i(TAG, "Server message: $message")
    }

    private inner class WsvpnWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")

            // Check command serialization header
            val serialization = response.header("Command-Serialization")
            if (serialization != null && serialization != "json") {
                listener.onError("Unsupported serialization: $serialization")
                disconnect()
                return
            }

            connected = true
            listener.onConnected()

            // Send our version
            val features = mutableListOf<String>()
            features.add("fragmentation")

            sendCommand(WsvpnCommand.version(PROTOCOL_VERSION, APP_VERSION, features))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val cmd = WsvpnCommand.fromJson(text)
                handleCommand(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse command: $text", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            if (data.isEmpty()) return

            val packet = if (fragmentationEnabled && defragmenter != null) {
                defragmenter!!.processMessage(data)
            } else {
                data
            }

            if (packet != null) {
                listener.onDataPacket(packet)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
            connected = false
            initialized = false
            listener.onDisconnected(reason.ifEmpty { "Connection closed (code=$code)" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            connected = false
            initialized = false
            listener.onError(t.message ?: "Connection failed")
            listener.onDisconnected(t.message ?: "Connection failed")
        }
    }
}
