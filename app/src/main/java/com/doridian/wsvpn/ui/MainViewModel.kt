package com.doridian.wsvpn.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doridian.wsvpn.data.AppFilterMode
import com.doridian.wsvpn.data.VpnProfile
import com.doridian.wsvpn.data.VpnSettingsRepository
import com.doridian.wsvpn.vpn.WsvpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

data class MainUiState(
    val profile: VpnProfile = VpnProfile(),
    val vpnState: WsvpnService.VpnState = WsvpnService.VpnState.Disconnected(""),
    val installedApps: List<AppInfo> = emptyList(),
    val appSearchQuery: String = "",
    val showSystemApps: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnSettingsRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.profile.collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }

        // Listen for VPN state changes
        WsvpnService.stateListener = { state ->
            _uiState.update { it.copy(vpnState = state) }
        }
        _uiState.update { it.copy(vpnState = WsvpnService.currentState) }
    }

    override fun onCleared() {
        WsvpnService.stateListener = null
        super.onCleared()
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(profile = it.profile.copy(serverUrl = url)) }
    }

    fun updateUsername(username: String) {
        _uiState.update { it.copy(profile = it.profile.copy(username = username)) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(profile = it.profile.copy(password = password)) }
    }

    fun updateInsecureTls(insecure: Boolean) {
        _uiState.update { it.copy(profile = it.profile.copy(insecureTls = insecure)) }
    }

    fun updateAutoReconnect(auto: Boolean) {
        _uiState.update { it.copy(profile = it.profile.copy(autoReconnect = auto)) }
    }

    fun updateAppFilterMode(mode: AppFilterMode) {
        _uiState.update { it.copy(profile = it.profile.copy(appFilterMode = mode)) }
    }

    fun toggleAppFilter(packageName: String) {
        _uiState.update { state ->
            val current = state.profile.filteredApps
            val updated = if (current.contains(packageName)) {
                current - packageName
            } else {
                current + packageName
            }
            state.copy(profile = state.profile.copy(filteredApps = updated))
        }
    }

    fun updateAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun toggleShowSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { appInfo ->
                    AppInfo(
                        packageName = appInfo.packageName,
                        label = appInfo.loadLabel(pm).toString(),
                        icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null },
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.label.lowercase() }
            _uiState.update { it.copy(installedApps = apps) }
        }
    }

    fun saveAndConnect() {
        viewModelScope.launch {
            val profile = _uiState.value.profile
            repository.saveProfile(profile)

            val context = getApplication<Application>()
            val intent = Intent(context, WsvpnService::class.java).apply {
                action = WsvpnService.ACTION_CONNECT
                putExtra("server_url", profile.serverUrl)
                putExtra("username", profile.username)
                putExtra("password", profile.password)
                putExtra("insecure_tls", profile.insecureTls)
                putExtra("auto_reconnect", profile.autoReconnect)
                putExtra("app_filter_mode", profile.appFilterMode.name)
                putExtra("filtered_apps", profile.filteredApps.toTypedArray())
            }
            context.startForegroundService(intent)
        }
    }

    fun disconnect() {
        val context = getApplication<Application>()
        val intent = Intent(context, WsvpnService::class.java).apply {
            action = WsvpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun saveSettings() {
        viewModelScope.launch {
            repository.saveProfile(_uiState.value.profile)
        }
    }

    fun getVpnPrepareIntent(): Intent? {
        return VpnService.prepare(getApplication())
    }
}
