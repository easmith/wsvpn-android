package com.doridian.wsvpn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

data class VpnServer(
    val id: String,
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = ""
)

data class VpnProfile(
    val insecureTls: Boolean = false,
    val autoReconnect: Boolean = true,
    val killSwitch: Boolean = true,
    val keepaliveSeconds: Int = 30,
    val appFilterMode: AppFilterMode = AppFilterMode.ALL,
    val filteredApps: Set<String> = emptySet()
)

enum class AppFilterMode {
    ALL,        // All apps use VPN
    ALLOWED,    // Only selected apps use VPN (whitelist)
    DISALLOWED  // All except selected apps use VPN (blacklist)
}

class VpnSettingsRepository(private val context: Context) {

    private val gson = Gson()
    private val serverListType = object : TypeToken<List<VpnServer>>() {}.type

    companion object {
        private val INSECURE_TLS = booleanPreferencesKey("insecure_tls")
        private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        private val KEEPALIVE_SECONDS = intPreferencesKey("keepalive_seconds")
        private val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        private val FILTERED_APPS = stringSetPreferencesKey("filtered_apps")

        private val SERVERS_JSON = stringPreferencesKey("servers")
        private val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")

        // Legacy keys (single-profile era) — kept for migration only.
        private val LEGACY_SERVER_URL = stringPreferencesKey("server_url")
        private val LEGACY_USERNAME = stringPreferencesKey("username")
        private val LEGACY_PASSWORD = stringPreferencesKey("password")
    }

    val profile: Flow<VpnProfile> = context.dataStore.data.map { prefs ->
        VpnProfile(
            insecureTls = prefs[INSECURE_TLS] ?: false,
            autoReconnect = prefs[AUTO_RECONNECT] ?: true,
            killSwitch = prefs[KILL_SWITCH] ?: true,
            keepaliveSeconds = prefs[KEEPALIVE_SECONDS] ?: 30,
            appFilterMode = try {
                AppFilterMode.valueOf(prefs[APP_FILTER_MODE] ?: "ALL")
            } catch (_: Exception) {
                AppFilterMode.ALL
            },
            filteredApps = prefs[FILTERED_APPS] ?: emptySet()
        )
    }

    val servers: Flow<List<VpnServer>> = context.dataStore.data.map { prefs ->
        decodeServers(prefs[SERVERS_JSON])
    }

    val selectedServerId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_SERVER_ID]
    }

    suspend fun saveProfile(profile: VpnProfile) {
        context.dataStore.edit { prefs ->
            prefs[INSECURE_TLS] = profile.insecureTls
            prefs[AUTO_RECONNECT] = profile.autoReconnect
            prefs[KILL_SWITCH] = profile.killSwitch
            prefs[KEEPALIVE_SECONDS] = profile.keepaliveSeconds
            prefs[APP_FILTER_MODE] = profile.appFilterMode.name
            prefs[FILTERED_APPS] = profile.filteredApps
        }
    }

    suspend fun upsertServer(server: VpnServer) {
        context.dataStore.edit { prefs ->
            val list = decodeServers(prefs[SERVERS_JSON]).toMutableList()
            val idx = list.indexOfFirst { it.id == server.id }
            if (idx >= 0) list[idx] = server else list.add(server)
            prefs[SERVERS_JSON] = gson.toJson(list)
        }
    }

    suspend fun deleteServer(id: String) {
        context.dataStore.edit { prefs ->
            val list = decodeServers(prefs[SERVERS_JSON]).filterNot { it.id == id }
            prefs[SERVERS_JSON] = gson.toJson(list)
            if (prefs[SELECTED_SERVER_ID] == id) {
                prefs.remove(SELECTED_SERVER_ID)
            }
        }
    }

    suspend fun setSelectedServerId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(SELECTED_SERVER_ID) else prefs[SELECTED_SERVER_ID] = id
        }
    }

    suspend fun getServer(id: String): VpnServer? {
        return servers.first().firstOrNull { it.id == id }
    }

    /**
     * On first launch after the multi-server upgrade, fold the old single-profile keys
     * (server_url / username / password) into one entry of the new servers list.
     */
    suspend fun migrateLegacyProfileIfNeeded() {
        context.dataStore.edit { prefs ->
            if (prefs.contains(SERVERS_JSON)) return@edit
            val legacyUrl = prefs[LEGACY_SERVER_URL]
            val legacyUser = prefs[LEGACY_USERNAME] ?: ""
            val legacyPass = prefs[LEGACY_PASSWORD] ?: ""
            val list = if (!legacyUrl.isNullOrBlank()) {
                val server = VpnServer(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    serverUrl = legacyUrl,
                    username = legacyUser,
                    password = legacyPass
                )
                prefs[SELECTED_SERVER_ID] = server.id
                listOf(server)
            } else {
                emptyList()
            }
            prefs[SERVERS_JSON] = gson.toJson(list)
            prefs.remove(LEGACY_SERVER_URL)
            prefs.remove(LEGACY_USERNAME)
            prefs.remove(LEGACY_PASSWORD)
        }
    }

    private fun decodeServers(json: String?): List<VpnServer> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<VpnServer>>(json, serverListType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
