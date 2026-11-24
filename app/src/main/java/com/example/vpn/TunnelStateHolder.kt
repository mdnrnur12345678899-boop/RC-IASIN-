package com.example.vpn

import com.example.data.ProxyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

data class TunnelLogEntry(
    val id: Long,
    val timeLabel: String,
    val level: LogLevel,
    val message: String,
)

data class TunnelRuntimeState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val activeConfig: ProxyConfig? = null,
    val bytesDownloaded: Long = 0L,
    val bytesUploaded: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val activeSockets: Int = 0,
    val connectedSinceMs: Long? = null,
    val exitIp: String? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
)

object TunnelStateHolder {
    private val _state = MutableStateFlow(TunnelRuntimeState())
    val state: StateFlow<TunnelRuntimeState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<TunnelLogEntry>>(emptyList())
    val logs: StateFlow<List<TunnelLogEntry>> = _logs.asStateFlow()

    private var nextLogId = 1L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setConnecting(config: ProxyConfig) {
        _state.value = TunnelRuntimeState(
            status = TunnelStatus.CONNECTING,
            activeConfig = config
        )
        log(LogLevel.INFO, "Starting ${config.protocol.displayName} tunnel to ${config.host}:${config.port}...")
    }

    fun setConnected(config: ProxyConfig, exitIp: String?, latencyMs: Long?) {
        _state.update {
            it.copy(
                status = TunnelStatus.CONNECTED,
                activeConfig = config,
                connectedSinceMs = System.currentTimeMillis(),
                exitIp = exitIp,
                latencyMs = latencyMs,
                errorMessage = null
            )
        }
        val extra = buildString {
            if (!exitIp.isNullOrBlank()) append(" | Exit IP: $exitIp")
            if (latencyMs != null) append(" (${latencyMs}ms)")
        }
        log(LogLevel.SUCCESS, "VPN tunnel active via ${config.host}:${config.port}$extra")
    }

    fun updateExitInfo(exitIp: String, latencyMs: Long) {
        _state.update {
            it.copy(exitIp = exitIp, latencyMs = latencyMs)
        }
    }

    fun updateTraffic(
        bytesDownloaded: Long,
        bytesUploaded: Long,
        downloadSpeedBps: Long,
        uploadSpeedBps: Long,
        activeSockets: Int,
    ) {
        _state.update {
            if (it.status == TunnelStatus.CONNECTED || it.status == TunnelStatus.CONNECTING) {
                it.copy(
                    bytesDownloaded = bytesDownloaded,
                    bytesUploaded = bytesUploaded,
                    downloadSpeedBps = downloadSpeedBps,
                    uploadSpeedBps = uploadSpeedBps,
                    activeSockets = activeSockets
                )
            } else {
                it
            }
        }
    }

    fun setError(message: String, config: ProxyConfig? = _state.value.activeConfig) {
        _state.value = TunnelRuntimeState(
            status = TunnelStatus.ERROR,
            activeConfig = config,
            errorMessage = message
        )
        log(LogLevel.ERROR, message)
    }

    fun setDisconnected(reason: String? = null) {
        val current = _state.value
        _state.value = TunnelRuntimeState(
            status = TunnelStatus.DISCONNECTED,
            activeConfig = current.activeConfig
        )
        if (!reason.isNullOrBlank()) {
            log(LogLevel.INFO, reason)
        }
    }

    fun log(level: LogLevel, message: String) {
        val entry = TunnelLogEntry(
            id = nextLogId++,
            timeLabel = timeFormat.format(Date()),
            level = level,
            message = message
        )
        _logs.update { current ->
            (listOf(entry) + current).take(120)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
