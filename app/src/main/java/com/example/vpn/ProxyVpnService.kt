package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppPreferences
import com.example.data.AppRoutingMode
import com.example.data.ProxyConfig
import com.example.data.ProxyProtocol
import com.example.network.ProxyHandshake
import com.example.network.ProxyTester
import com.example.security.AppIntegrityGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProxyVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"

        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_LABEL = "extra_label"

        private const val PREFS_NAME = "rcproxy_service_prefs"
        private const val KEY_ACTIVE = "is_active"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LABEL = "label"

        private const val CHANNEL_ID = "rcproxy_tunnel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var activeInstance: ProxyVpnService? = null

        fun startConnect(context: Context, config: ProxyConfig) {
            AppIntegrityGuard.enforceOrCrash(context)
            val intent = Intent(context, ProxyVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROTOCOL, config.protocol.name)
                putExtra(EXTRA_HOST, config.host)
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_USERNAME, config.username)
                putExtra(EXTRA_PASSWORD, config.password)
                putExtra(EXTRA_LABEL, config.label)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTunnel(context: Context) {
            val instance = activeInstance
            if (instance != null) {
                instance.disconnectImmediate()
                return
            }
            val intent = Intent(context, ProxyVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val started = runCatching { context.startService(intent) }.isSuccess
            if (!started) {
                TunnelStateHolder.setDisconnected("Proxy disconnected.")
            }
        }
    }

    private val serviceErrorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        TunnelStateHolder.log(
            LogLevel.ERROR,
            "VPN service error: ${throwable.message ?: throwable::class.java.simpleName}"
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceErrorHandler)
    private var connectJob: Job? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var packetEngine: TunPacketEngine? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    private fun disconnectImmediate() {
        clearPersistedConfig()
        stopVpnTunnel("Proxy disconnected by user.")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppIntegrityGuard.enforceOrCrash(this)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectImmediate()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (host.isEmpty() || port !in 1..65535) {
                    clearPersistedConfig()
                    TunnelStateHolder.setError("Invalid proxy host or port.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = ProxyConfig(
                    protocol = ProxyProtocol.fromString(intent.getStringExtra(EXTRA_PROTOCOL)),
                    host = host,
                    port = port,
                    username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                )
                persistActiveConfig(config)
                AppPreferences.saveLastProxyConfig(this, config)
                startForegroundWithNotification(config, isConnecting = true)
                establishTunnel(config)
            }
            null -> {
                // Restored by OS under START_STICKY while running in background
                val restored = loadPersistedConfig()
                if (restored != null) {
                    val fgsOk = runCatching {
                        startForegroundWithNotification(restored, isConnecting = true)
                    }.isSuccess
                    if (!fgsOk) {
                        clearPersistedConfig()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    establishTunnel(restored)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep VPN proxy running in background even if user removes app from Recents
        super.onTaskRemoved(rootIntent)
    }

    private fun establishTunnel(config: ProxyConfig) {
        connectJob?.cancel()
        tearDownEngineOnly()
        TunnelStateHolder.setConnecting(config)

        connectJob = serviceScope.launch {
            AppIntegrityGuard.enforceOrCrash(this@ProxyVpnService)

            val handshakeCheck = runCatching {
                val testStart = System.currentTimeMillis()
                ProxyHandshake.connectViaProxy(
                    config = config,
                    targetHost = "1.1.1.1",
                    targetPort = 443,
                    connectTimeoutMs = 7000,
                    readTimeoutMs = 5000
                ).use { }
                System.currentTimeMillis() - testStart
            }

            if (handshakeCheck.isFailure) {
                clearPersistedConfig()
                val msg = handshakeCheck.exceptionOrNull()?.message
                    ?: "Could not authenticate or reach ${config.host}:${config.port}"
                TunnelStateHolder.setError(msg, config)
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            val handshakeMs = handshakeCheck.getOrNull()

            val builder = Builder()
                .setSession("RC PROXY - ${config.displayTitle}")
                .setMtu(1500)
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            // Capture IPv6 so dual-stack cellular networks don't bypass the proxy
            runCatching {
                builder.addAddress("fd00:10:8::2", 128)
                builder.addRoute("::", 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                runCatching { builder.setUnderlyingNetworks(null) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val routingConfig = AppPreferences.getRoutingConfig(this@ProxyVpnService)
            val activeSelection = routingConfig.activeSelectedApps
                .asSequence()
                .filter { it.isNotBlank() && it != packageName }
                .toSet()

            if (routingConfig.mode == AppRoutingMode.ALLOW_SELECTED && activeSelection.isNotEmpty()) {
                var addedCount = 0
                activeSelection.forEach { pkg ->
                    if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) {
                        addedCount++
                    }
                }
                if (addedCount > 0) {
                    TunnelStateHolder.log(
                        LogLevel.INFO,
                        "App Routing: Allowed mode ($addedCount selected apps)"
                    )
                } else {
                    runCatching { builder.addDisallowedApplication(packageName) }
                    TunnelStateHolder.log(LogLevel.INFO, "App Routing: All Apps")
                }
            } else {
                // Always exclude RC Proxy itself so proxy sockets don't loop back into TUN
                runCatching { builder.addDisallowedApplication(packageName) }
                if (routingConfig.mode == AppRoutingMode.DISALLOW_SELECTED && activeSelection.isNotEmpty()) {
                    var excludedCount = 0
                    activeSelection.forEach { pkg ->
                        if (runCatching { builder.addDisallowedApplication(pkg) }.isSuccess) {
                            excludedCount++
                        }
                    }
                    TunnelStateHolder.log(
                        LogLevel.INFO,
                        "App Routing: Don't Allow mode ($excludedCount excluded apps)"
                    )
                } else {
                    TunnelStateHolder.log(LogLevel.INFO, "App Routing: All Apps")
                }
            }

            val fd = builder.establish()
            if (fd == null) {
                clearPersistedConfig()
                TunnelStateHolder.setError("Failed to establish Android VPN TUN interface.", config)
                stopForegroundCompat()
                stopSelf()
                return@launch
            }

            tunInterface = fd
            val engine = TunPacketEngine(
                tunFd = fd,
                config = config,
                protectSocket = { socket -> protect(socket) },
                onFatalAuthFailure = { errorMsg ->
                    serviceScope.launch {
                        clearPersistedConfig()
                        stopVpnTunnel(null)
                        TunnelStateHolder.setError(errorMsg, config)
                        stopSelf()
                    }
                }
            )
            packetEngine = engine
            engine.start()

            updateNotification(config, isConnecting = false)
            TunnelStateHolder.setConnected(
                config = config,
                exitIp = null,
                latencyMs = handshakeMs
            )

            serviceScope.launch {
                ProxyTester.verifyProxy(config).onSuccess { result ->
                    TunnelStateHolder.updateExitInfo(result.exitIp, result.latencyMs)
                    TunnelStateHolder.log(
                        LogLevel.SUCCESS,
                        "Exit IP verified: ${result.exitIp} (${result.latencyMs}ms)"
                    )
                }
            }
        }
    }

    private fun startForegroundWithNotification(config: ProxyConfig, isConnecting: Boolean) {
        createNotificationChannel()
        val notification = buildNotification(config, isConnecting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(config: ProxyConfig, isConnecting: Boolean) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(config, isConnecting))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(config: ProxyConfig, isConnecting: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (isConnecting) {
            "Authenticating ${config.protocol.displayName} ${config.host}:${config.port}…"
        } else {
            "${config.protocol.displayName} • ${config.host}:${config.port} (All Apps)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.notification_title_connected))
            .setContentText(contentText)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_disconnect), disconnectIntent)
            .build()
    }

    private fun persistActiveConfig(config: ProxyConfig) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_PROTOCOL, config.protocol.name)
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_LABEL, config.label)
            .apply()
    }

    private fun clearPersistedConfig() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
    }

    private fun loadPersistedConfig(): ProxyConfig? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, 0)
        if (host.isEmpty() || port !in 1..65535) return null
        return ProxyConfig(
            protocol = ProxyProtocol.fromString(prefs.getString(KEY_PROTOCOL, null)),
            host = host,
            port = port,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            label = prefs.getString(KEY_LABEL, "").orEmpty()
        )
    }

    private fun tearDownEngineOnly() {
        packetEngine?.stop()
        packetEngine = null
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun stopVpnTunnel(disconnectReason: String?) {
        connectJob?.cancel()
        connectJob = null
        tearDownEngineOnly()
        stopForegroundCompat()
        if (disconnectReason != null) {
            TunnelStateHolder.setDisconnected(disconnectReason)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        clearPersistedConfig()
        stopVpnTunnel("VPN permission revoked by system.")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        tearDownEngineOnly()
        serviceScope.cancel()
        super.onDestroy()
    }
}
