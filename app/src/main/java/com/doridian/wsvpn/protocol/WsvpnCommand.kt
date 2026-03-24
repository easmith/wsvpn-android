package com.doridian.wsvpn.protocol

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class WsvpnCommand(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val parameters: JsonObject = JsonObject()
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): WsvpnCommand = gson.fromJson(json, WsvpnCommand::class.java)

        fun version(protocolVersion: Int, appVersion: String, features: List<String>): WsvpnCommand {
            val params = JsonObject().apply {
                addProperty("protocol_version", protocolVersion)
                addProperty("version", appVersion)
                add("enabled_features", gson.toJsonTree(features))
            }
            return WsvpnCommand(command = "version", parameters = params)
        }

        fun reply(replyTo: String, error: String = ""): WsvpnCommand {
            val params = JsonObject().apply {
                addProperty("error", error)
            }
            return WsvpnCommand(id = replyTo, command = "reply", parameters = params)
        }
    }

    fun toJson(): String = gson.toJson(this)
}

data class InitParameters(
    val mode: String = "TUN",
    @SerializedName("do_ip_config") val doIpConfig: Boolean = true,
    val mtu: Int = 1420,
    @SerializedName("ip_address") val ipAddress: String = "",
    @SerializedName("server_id") val serverId: String = "",
    @SerializedName("client_id") val clientId: String = ""
) {
    companion object {
        private val gson = Gson()
        fun fromJsonObject(obj: JsonObject): InitParameters = gson.fromJson(obj, InitParameters::class.java)
    }
}

data class VersionParameters(
    @SerializedName("protocol_version") val protocolVersion: Int = 0,
    val version: String = "",
    @SerializedName("enabled_features") val enabledFeatures: List<String> = emptyList()
) {
    companion object {
        private val gson = Gson()
        fun fromJsonObject(obj: JsonObject): VersionParameters = gson.fromJson(obj, VersionParameters::class.java)
    }
}

data class AddRouteParameters(
    val route: String = ""
) {
    companion object {
        private val gson = Gson()
        fun fromJsonObject(obj: JsonObject): AddRouteParameters = gson.fromJson(obj, AddRouteParameters::class.java)
    }
}

data class SetMtuParameters(
    val mtu: Int = 0
) {
    companion object {
        private val gson = Gson()
        fun fromJsonObject(obj: JsonObject): SetMtuParameters = gson.fromJson(obj, SetMtuParameters::class.java)
    }
}
