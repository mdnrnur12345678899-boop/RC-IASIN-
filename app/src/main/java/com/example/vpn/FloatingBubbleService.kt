package com.example.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppPreferences
import com.example.security.AppIntegrityGuard
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START_BUBBLE = "com.example.vpn.ACTION_START_BUBBLE"
        const val ACTION_STOP_BUBBLE = "com.example.vpn.ACTION_STOP_BUBBLE"

        private const val CHANNEL_ID = "rcproxy_bubble_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            AppPreferences.setBubbleEnabled(context, true)
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_START_BUBBLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            AppPreferences.setBubbleEnabled(context, false)
            runCatching {
                context.stopService(Intent(context, FloatingBubbleService::class.java))
            }
        }
    }

    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        TunnelStateHolder.log(
            LogLevel.WARN,
            "Bubble service recovered: ${throwable.message ?: throwable::class.java.simpleName}"
        )
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)

    private var windowManager: WindowManager? = null
    private var bubbleContainer: LinearLayout? = null
    private var iconContainer: LinearLayout? = null
    private var statusBadge: TextView? = null
    private var shieldIcon: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppIntegrityGuard.enforceOrCrash(this)
        if (intent?.action == ACTION_STOP_BUBBLE) {
            AppPreferences.setBubbleEnabled(this, false)
            TunnelStateHolder.setBubbleRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AppPreferences.setBubbleEnabled(this, false)
            TunnelStateHolder.setBubbleRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val fgsStarted = runCatching {
            startForegroundWithNotification()
        }.isSuccess
        if (!fgsStarted) {
            stopSelf()
            return START_NOT_STICKY
        }

        AppPreferences.setBubbleEnabled(this, true)
        TunnelStateHolder.setBubbleRunning(true)

        if (bubbleContainer == null) {
            createBubbleOverlay()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally keep floating bubble alive even when user removes RC Proxy from Recents
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = resources.displayMetrics.density
        val circlePx = (58 * density).toInt()
        val iconPx = (26 * density).toInt()
        val padH = (8 * density).toInt()
        val padV = (2 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            elevation = 10f * density
        }
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_vpn_notification)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.app_name)
        }
        circle.addView(iv, LinearLayout.LayoutParams(iconPx, iconPx))

        val label = TextView(this).apply {
            text = "OFF"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
        }

        root.addView(circle, LinearLayout.LayoutParams(circlePx, circlePx))
        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (3 * density).toInt()
        }
        root.addView(label, badgeParams)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (220 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isClick = true
        val touchSlopPx = 12f * density

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                        isClick = false
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleTapped()
                    } else {
                        // Snap to nearest horizontal edge
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = root.width.coerceAtLeast(circlePx)
                        val centerX = params.x + bubbleWidth / 2
                        params.x = if (centerX < screenWidth / 2) {
                            (12 * density).toInt()
                        } else {
                            (screenWidth - bubbleWidth - (12 * density).toInt()).coerceAtLeast(0)
                        }
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(root, params)
            bubbleContainer = root
            iconContainer = circle
            shieldIcon = iv
            statusBadge = label
            layoutParams = params
        }.onFailure {
            stopSelf()
            return
        }

        serviceScope.launch {
            TunnelStateHolder.state.collect { state ->
                updateBubbleAppearance(state.status)
            }
        }
    }

    private fun updateBubbleAppearance(status: TunnelStatus) {
        val density = resources.displayMetrics.density
        val circle = iconContainer ?: return
        val badge = statusBadge ?: return
        val icon = shieldIcon ?: return

        val (ringColor, fillColor, badgeBg, badgeText) = when (status) {
            TunnelStatus.CONNECTED -> Quad(
                0xFF00E5A3.toInt(),
                0xEE0F172A.toInt(),
                0xFF065F46.toInt(),
                "ON"
            )
            TunnelStatus.CONNECTING -> Quad(
                0xFFF59E0B.toInt(),
                0xEE0F172A.toInt(),
                0xFF78350F.toInt(),
                "..."
            )
            TunnelStatus.ERROR -> Quad(
                0xFFFF5C77.toInt(),
                0xEE1F1221.toInt(),
                0xFF7F1D1D.toInt(),
                "ERR"
            )
            TunnelStatus.DISCONNECTED -> Quad(
                0xFF64748B.toInt(),
                0xEE0F172A.toInt(),
                0xFF1E293B.toInt(),
                "OFF"
            )
        }

        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke((3 * density).toInt(), ringColor)
        }
        icon.setColorFilter(ringColor)

        badge.text = badgeText
        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(badgeBg)
            setStroke((1 * density).toInt(), ringColor)
        }
    }

    private fun onBubbleTapped() {
        AppIntegrityGuard.enforceOrCrash(this)
        serviceScope.launch(Dispatchers.IO) {
            val status = TunnelStateHolder.state.value.status
            if (status == TunnelStatus.CONNECTED || status == TunnelStatus.CONNECTING) {
                ProxyVpnService.stopTunnel(this@FloatingBubbleService)
                return@launch
            }

            val savedConfig = AppPreferences.loadLastProxyConfig(this@FloatingBubbleService)
            if (savedConfig == null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@FloatingBubbleService,
                        "Enter proxy Server & Port in RC Proxy first.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            val prepareIntent = runCatching { VpnService.prepare(this@FloatingBubbleService) }.getOrNull()
            if (prepareIntent == null) {
                val started = runCatching {
                    ProxyVpnService.startConnect(this@FloatingBubbleService, savedConfig)
                }.isSuccess
                if (!started) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@FloatingBubbleService,
                            "Could not start proxy service from background.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else {
                // Only pop the system VPN permission dialog via translucent activity, never MainActivity
                val consentIntent = Intent(this@FloatingBubbleService, VpnConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(consentIntent) }
            }
        }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopBubbleIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP_BUBBLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.bubble_action_hide), stopBubbleIntent)
            .build()

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

    override fun onDestroy() {
        TunnelStateHolder.setBubbleRunning(false)
        bubbleContainer?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubbleContainer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class Quad(
        val ring: Int,
        val fill: Int,
        val badgeBg: Int,
        val label: String,
    )
}
