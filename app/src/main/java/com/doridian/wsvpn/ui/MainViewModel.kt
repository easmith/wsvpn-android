package com.doridian.wsvpn.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.doridian.wsvpn.data.AppFilterMode
import com.doridian.wsvpn.data.VpnProfile
import com.doridian.wsvpn.data.VpnServer
import com.doridian.wsvpn.data.VpnSettingsRepository
import com.doridian.wsvpn.vpn.WsvpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean
)

data class ConnectionState(
    val vpnState: WsvpnService.VpnState = WsvpnService.VpnState.Disconnected(""),
    val activeServerId: String? = null
)

data class AppListState(
    val installedApps: List<AppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val showSelectedOnly: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnSettingsRepository(application)

    private val _profile = MutableStateFlow(VpnProfile())
    val profile: StateFlow<VpnProfile> = _profile.asStateFlow()

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _appList = MutableStateFlow(AppListState())
    val appList: StateFlow<AppListState> = _appList.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            repository.migrateLegacyProfileIfNeeded()
        }

        viewModelScope.launch {
            repository.profile.collect { profile ->
                _profile.value = profile
            }
        }

        viewModelScope.launch {
            repository.servers.collect { servers ->
                _servers.value = servers
            }
        }

        WsvpnService.stateListener = { state, activeId ->
            _connection.value = ConnectionState(state, activeId)
        }
        _connection.value = ConnectionState(
            vpnState = WsvpnService.currentState,
            activeServerId = WsvpnService.activeServerId
        )
    }

    override fun onCleared() {
        WsvpnService.stateListener = null
        // Flush any pending settings write so the user doesn't lose a toggle made
        // immediately before the activity is destroyed. Use a process-scoped
        // coroutine because viewModelScope is already cancelling.
        val pendingProfile = _profile.value
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            runBlocking { repository.saveProfile(pendingProfile) }
        }
        super.onCleared()
    }

    fun updateInsecureTls(insecure: Boolean) {
        _profile.update { it.copy(insecureTls = insecure) }
        scheduleSave()
    }

    fun updateAutoReconnect(auto: Boolean) {
        _profile.update { it.copy(autoReconnect = auto) }
        scheduleSave()
    }

    fun updateKillSwitch(enabled: Boolean) {
        _profile.update { it.copy(killSwitch = enabled) }
        scheduleSave()
    }

    fun updateKeepaliveSeconds(seconds: Int) {
        _profile.update { it.copy(keepaliveSeconds = seconds.coerceIn(15, 300)) }
        scheduleSave()
    }

    fun updateAppFilterMode(mode: AppFilterMode) {
        _profile.update { it.copy(appFilterMode = mode) }
        scheduleSave()
    }

    fun toggleAppFilter(packageName: String) {
        _profile.update { profile ->
            val current = profile.filteredApps
            val updated = if (current.contains(packageName)) current - packageName else current + packageName
            profile.copy(filteredApps = updated)
        }
        scheduleSave()
    }

    fun updateAppSearchQuery(query: String) {
        _appList.update { it.copy(searchQuery = query) }
    }

    fun toggleShowSystemApps() {
        _appList.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun toggleShowSelectedOnly() {
        _appList.update { it.copy(showSelectedOnly = !it.showSelectedOnly) }
    }

    fun loadInstalledApps() {
        val current = _appList.value
        if (current.installedApps.isNotEmpty() || current.isLoadingApps) return
        _appList.update { it.copy(isLoadingApps = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                // Icons used to be loaded here eagerly for every installed app — on
                // devices with 200+ apps that was tens of MB of Drawable allocations
                // up-front. Icons now load lazily per visible row (see AppIcon in
                // AppFilterScreen). GET_META_DATA isn't needed for label/flags either.
                pm.getInstalledApplications(0)
                    .map { appInfo ->
                        AppInfo(
                            packageName = appInfo.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _appList.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun connectServer(id: String) {
        viewModelScope.launch {
            val server = _servers.value.firstOrNull { it.id == id } ?: return@launch
            val profile = _profile.value
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
                putExtra(WsvpnService.EXTRA_KEEPALIVE_SECONDS, profile.keepaliveSeconds)
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
            if (_connection.value.activeServerId == id) {
                disconnect()
            }
            repository.deleteServer(id)
        }
    }

    fun getVpnPrepareIntent(): Intent? {
        return VpnService.prepare(getApplication())
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repository.saveProfile(_profile.value)
        }
    }
}
