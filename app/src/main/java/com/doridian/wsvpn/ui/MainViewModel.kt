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
import com.doridian.wsvpn.data.VpnServer
import com.doridian.wsvpn.data.VpnSettingsRepository
import com.doridian.wsvpn.vpn.WsvpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

data class MainUiState(
    val profile: VpnProfile = VpnProfile(),
    val servers: List<VpnServer> = emptyList(),
    val activeServerId: String? = null,
    val vpnState: WsvpnService.VpnState = WsvpnService.VpnState.Disconnected(""),
    val installedApps: List<AppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val appSearchQuery: String = "",
    val showSystemApps: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnSettingsRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.migrateLegacyProfileIfNeeded()
        }

        viewModelScope.launch {
            repository.profile.collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }

        viewModelScope.launch {
            repository.servers.collect { servers ->
                _uiState.update { it.copy(servers = servers) }
            }
        }

        WsvpnService.stateListener = { state, activeId ->
            _uiState.update { it.copy(vpnState = state, activeServerId = activeId) }
        }
        _uiState.update {
            it.copy(
                vpnState = WsvpnService.currentState,
                activeServerId = WsvpnService.activeServerId
            )
        }
    }

    override fun onCleared() {
        WsvpnService.stateListener = null
        super.onCleared()
    }

    fun updateInsecureTls(insecure: Boolean) {
        _uiState.update { it.copy(profile = it.profile.copy(insecureTls = insecure)) }
    }

    fun updateAutoReconnect(auto: Boolean) {
        _uiState.update { it.copy(profile = it.profile.copy(autoReconnect = auto)) }
    }

    fun updateKillSwitch(enabled: Boolean) {
        _uiState.update { it.copy(profile = it.profile.copy(killSwitch = enabled)) }
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
        val current = _uiState.value
        if (current.installedApps.isNotEmpty() || current.isLoadingApps) return
        _uiState.update { it.copy(isLoadingApps = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { appInfo ->
                        AppInfo(
                            packageName = appInfo.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null },
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _uiState.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun connectServer(id: String) {
        viewModelScope.launch {
            val server = _uiState.value.servers.firstOrNull { it.id == id } ?: return@launch
            val profile = _uiState.value.profile
            repository.setSelectedServerId(id)
            val context = getApplication<Application>()
            val intent = Intent(context, WsvpnService::class.java).apply {
                action = WsvpnService.ACTION_CONNECT
                putExtra(WsvpnService.EXTRA_SERVER_ID, server.id)
                putExtra(WsvpnService.EXTRA_SERVER_URL, server.serverUrl)
                putExtra(WsvpnService.EXTRA_USERNAME, server.username)
                putExtra(WsvpnService.EXTRA_PASSWORD, server.password)
                putExtra(WsvpnService.EXTRA_INSECURE_TLS, profile.insecureTls)
                putExtra(WsvpnService.EXTRA_AUTO_RECONNECT, profile.autoReconnect)
                putExtra(WsvpnService.EXTRA_KILL_SWITCH, profile.killSwitch)
                putExtra(WsvpnService.EXTRA_APP_FILTER_MODE, profile.appFilterMode.name)
                putExtra(WsvpnService.EXTRA_FILTERED_APPS, profile.filteredApps.toTypedArray())
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

    fun saveServer(server: VpnServer) {
        viewModelScope.launch {
            val id = server.id.ifBlank { UUID.randomUUID().toString() }
            repository.upsertServer(server.copy(id = id))
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch {
            if (_uiState.value.activeServerId == id) {
                disconnect()
            }
            repository.deleteServer(id)
        }
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
