package com.doridian.wsvpn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

data class VpnProfile(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val insecureTls: Boolean = false,
    val autoReconnect: Boolean = true,
    val appFilterMode: AppFilterMode = AppFilterMode.ALL,
    val filteredApps: Set<String> = emptySet()
)

enum class AppFilterMode {
    ALL,        // All apps use VPN
    ALLOWED,    // Only selected apps use VPN (whitelist)
    DISALLOWED  // All except selected apps use VPN (blacklist)
}

class VpnSettingsRepository(private val context: Context) {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val INSECURE_TLS = booleanPreferencesKey("insecure_tls")
        private val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        private val FILTERED_APPS = stringSetPreferencesKey("filtered_apps")
    }

    val profile: Flow<VpnProfile> = context.dataStore.data.map { prefs ->
        VpnProfile(
            serverUrl = prefs[SERVER_URL] ?: "",
            username = prefs[USERNAME] ?: "",
            password = prefs[PASSWORD] ?: "",
            insecureTls = prefs[INSECURE_TLS] ?: false,
            autoReconnect = prefs[AUTO_RECONNECT] ?: true,
            appFilterMode = try {
                AppFilterMode.valueOf(prefs[APP_FILTER_MODE] ?: "ALL")
            } catch (_: Exception) {
                AppFilterMode.ALL
            },
            filteredApps = prefs[FILTERED_APPS] ?: emptySet()
        )
    }

    suspend fun saveProfile(profile: VpnProfile) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL] = profile.serverUrl
            prefs[USERNAME] = profile.username
            prefs[PASSWORD] = profile.password
            prefs[INSECURE_TLS] = profile.insecureTls
            prefs[AUTO_RECONNECT] = profile.autoReconnect
            prefs[APP_FILTER_MODE] = profile.appFilterMode.name
            prefs[FILTERED_APPS] = profile.filteredApps
        }
    }
}
