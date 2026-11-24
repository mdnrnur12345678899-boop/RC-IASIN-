package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.AppDatabase
import com.example.data.ProxyConfig
import com.example.data.ProxyProfile
import com.example.data.ProxyProtocol
import com.example.data.ProxyRepository
import com.example.network.ProxyTestResult
import com.example.network.ProxyTester
import com.example.vpn.LogLevel
import com.example.vpn.ProxyVpnService
import com.example.vpn.TunnelLogEntry
import com.example.vpn.TunnelRuntimeState
import com.example.vpn.TunnelStateHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProxyFormState(
    val editingProfileId: Long = 0L,
    val label: String = "RC PROXY",
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val host: String = "",
    val portText: String = "1080",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val hostError: String? = null,
    val portError: String? = null,
)

sealed interface ProxyTestUiState {
    data object Idle : ProxyTestUiState
    data object Testing : ProxyTestUiState
    data class Success(val result: ProxyTestResult) : ProxyTestUiState
    data class Failure(val message: String) : ProxyTestUiState
}

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProxyRepository(
        AppDatabase.getInstance(application).proxyProfileDao()
    )

    private val _formState = MutableStateFlow(ProxyFormState())
    val formState: StateFlow<ProxyFormState> = _formState.asStateFlow()

    private val _testState = MutableStateFlow<ProxyTestUiState>(ProxyTestUiState.Idle)
    val testState: StateFlow<ProxyTestUiState> = _testState.asStateFlow()

    private val _uiMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiMessage: SharedFlow<String> = _uiMessage.asSharedFlow()

    val profiles: StateFlow<List<ProxyProfile>> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tunnelState: StateFlow<TunnelRuntimeState> = TunnelStateHolder.state
    val logs: StateFlow<List<TunnelLogEntry>> = TunnelStateHolder.logs

    private var pendingConfigForVpnConsent: ProxyConfig? = null

    init {
        viewModelScope.launch {
            val last = repository.getLastUsed()
            if (last != null) {
                populateFromProfile(last)
            }
        }
    }

    fun onProtocolChange(protocol: ProxyProtocol) {
        _formState.update { state ->
            val updatedPort = when {
                state.portText == "1080" && protocol == ProxyProtocol.HTTP -> "8080"
                state.portText == "8080" && protocol == ProxyProtocol.SOCKS5 -> "1080"
                else -> state.portText
            }
            state.copy(protocol = protocol, portText = updatedPort)
        }
        _testState.value = ProxyTestUiState.Idle
    }

    fun onLabelChange(value: String) {
        _formState.update { it.copy(label = value) }
    }

    fun onHostChange(value: String) {
        _formState.update { it.copy(host = value, hostError = null) }
        _testState.value = ProxyTestUiState.Idle
    }

    fun onPortChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(5)
        _formState.update { it.copy(portText = filtered, portError = null) }
        _testState.value = ProxyTestUiState.Idle
    }

    fun onUsernameChange(value: String) {
        _formState.update { it.copy(username = value) }
        _testState.value = ProxyTestUiState.Idle
    }

    fun onPasswordChange(value: String) {
        _formState.update { it.copy(password = value) }
        _testState.value = ProxyTestUiState.Idle
    }

    fun togglePasswordVisibility() {
        _formState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun clearFormForNew() {
        _formState.value = ProxyFormState()
        _testState.value = ProxyTestUiState.Idle
    }

    fun populateFromProfile(profile: ProxyProfile) {
        _formState.value = ProxyFormState(
            editingProfileId = profile.id,
            label = profile.name,
            protocol = profile.protocol,
            host = profile.host,
            portText = profile.port.toString(),
            username = profile.username,
            password = profile.password
        )
        _testState.value = ProxyTestUiState.Idle
    }

    fun saveCurrentProfile() {
        val config = validateAndBuildConfig() ?: return
        val currentId = _formState.value.editingProfileId
        viewModelScope.launch {
            val savedId = repository.saveProfile(
                ProxyProfile(
                    id = currentId,
                    name = config.displayTitle,
                    protocol = config.protocol,
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = config.password
                )
            )
            _formState.update { it.copy(editingProfileId = savedId, label = config.displayTitle) }
            _uiMessage.tryEmit("Saved profile \"${config.displayTitle}\"")
        }
    }

    fun deleteProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            repository.deleteProfile(profile.id)
            if (_formState.value.editingProfileId == profile.id) {
                _formState.update { it.copy(editingProfileId = 0L) }
            }
            _uiMessage.tryEmit("Deleted \"${profile.name}\"")
        }
    }

    fun testCurrentProxy() {
        val config = validateAndBuildConfig() ?: return
        _testState.value = ProxyTestUiState.Testing
        TunnelStateHolder.log(
            LogLevel.INFO,
            "Testing ${config.protocol.displayName} proxy ${config.host}:${config.port}…"
        )
        viewModelScope.launch {
            val result = ProxyTester.verifyProxy(config)
            result.onSuccess { info ->
                _testState.value = ProxyTestUiState.Success(info)
                TunnelStateHolder.log(
                    LogLevel.SUCCESS,
                    "Verified! Exit IP ${info.exitIp} • Ping ${info.latencyMs}ms (Handshake ${info.handshakeMs}ms)"
                )
                val id = _formState.value.editingProfileId
                if (id != 0L) {
                    repository.updateVerification(id, info.latencyMs, info.exitIp)
                }
            }.onFailure { err ->
                val msg = err.message ?: "Proxy test failed."
                _testState.value = ProxyTestUiState.Failure(msg)
                TunnelStateHolder.log(LogLevel.ERROR, "Verify failed: $msg")
            }
        }
    }

    fun requestConnect(
        context: Context,
        onRequireVpnConsent: (Intent) -> Unit,
    ) {
        val config = validateAndBuildConfig() ?: return
        val profileId = _formState.value.editingProfileId
        if (profileId != 0L) {
            viewModelScope.launch { repository.markLastUsed(profileId) }
        }

        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingConfigForVpnConsent = config
            onRequireVpnConsent(prepareIntent)
        } else {
            ProxyVpnService.startConnect(context, config)
        }
    }

    fun connectWithProfile(
        context: Context,
        profile: ProxyProfile,
        onRequireVpnConsent: (Intent) -> Unit,
    ) {
        populateFromProfile(profile)
        requestConnect(context, onRequireVpnConsent)
    }

    fun onVpnConsentResult(context: Context, granted: Boolean) {
        val config = pendingConfigForVpnConsent
        pendingConfigForVpnConsent = null
        if (granted && config != null) {
            ProxyVpnService.startConnect(context, config)
        } else if (!granted) {
            val msg = getApplication<Application>().getString(R.string.error_vpn_denied)
            TunnelStateHolder.setError(msg, config)
            _uiMessage.tryEmit(msg)
        }
    }

    fun disconnect(context: Context) {
        ProxyVpnService.stopTunnel(context)
    }

    fun clearLogs() {
        TunnelStateHolder.clearLogs()
    }

    private fun validateAndBuildConfig(): ProxyConfig? {
        val app = getApplication<Application>()
        val state = _formState.value
        val trimmedHost = state.host.trim()
        val parsedPort = state.portText.trim().toIntOrNull()

        var hostErr: String? = null
        var portErr: String? = null

        if (trimmedHost.isEmpty() || trimmedHost.contains(' ')) {
            hostErr = app.getString(R.string.error_empty_host)
        }
        if (parsedPort == null || parsedPort !in 1..65535) {
            portErr = app.getString(R.string.error_invalid_port)
        }

        if (hostErr != null || portErr != null) {
            _formState.update { it.copy(hostError = hostErr, portError = portErr) }
            return null
        }

        return ProxyConfig(
            protocol = state.protocol,
            host = trimmedHost,
            port = parsedPort!!,
            username = state.username,
            password = state.password,
            label = state.label.trim()
        )
    }
}
