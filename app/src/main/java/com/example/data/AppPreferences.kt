package com.example.data

import android.content.Context

enum class AppRoutingMode {
    ALLOW_SELECTED,
    DISALLOW_SELECTED;

    companion object {
        fun fromString(value: String?): AppRoutingMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DISALLOW_SELECTED
    }
}

data class AppRoutingConfig(
    val mode: AppRoutingMode = AppRoutingMode.DISALLOW_SELECTED,
    val allowedApps: Set<String> = emptySet(),
    val disallowedApps: Set<String> = emptySet(),
) {
    val activeSelectedApps: Set<String>
        get() = when (mode) {
            AppRoutingMode.ALLOW_SELECTED -> allowedApps
            AppRoutingMode.DISALLOW_SELECTED -> disallowedApps
        }

    val isAllAppsEffective: Boolean
        get() = activeSelectedApps.isEmpty()
}

object AppPreferences {
    private const val APP_PREFS = "rcproxy_app_prefs"
    private const val SERVICE_PREFS = "rcproxy_saved_config_prefs"

    private const val KEY_TELEGRAM_DISMISSED = "telegram_prompt_dismissed"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"

    private const val KEY_ROUTING_MODE = "routing_mode"
    private const val KEY_ALLOWED_APPS = "allowed_apps"
    private const val KEY_DISALLOWED_APPS = "disallowed_apps"

    private const val KEY_LAST_PROTOCOL = "last_protocol"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_LAST_PORT = "last_port"
    private const val KEY_LAST_USERNAME = "last_username"
    private const val KEY_LAST_PASSWORD = "last_password"
    private const val KEY_LAST_LABEL = "last_label"

    fun isTelegramPromptDismissed(context: Context): Boolean {
        return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TELEGRAM_DISMISSED, false)
    }

    fun setTelegramPromptDismissed(context: Context, dismissed: Boolean) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TELEGRAM_DISMISSED, dismissed)
            .apply()
    }

    fun isBubbleEnabled(context: Context): Boolean {
        return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUBBLE_ENABLED, false)
    }

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BUBBLE_ENABLED, enabled)
            .apply()
    }

    fun getRoutingConfig(context: Context): AppRoutingConfig {
        val prefs = context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
        val mode = AppRoutingMode.fromString(prefs.getString(KEY_ROUTING_MODE, null))
        val allowed = prefs.getStringSet(KEY_ALLOWED_APPS, emptySet())?.toSet().orEmpty()
        val disallowed = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet())?.toSet().orEmpty()
        return AppRoutingConfig(
            mode = mode,
            allowedApps = allowed,
            disallowedApps = disallowed
        )
    }

    fun saveRoutingMode(context: Context, mode: AppRoutingMode) {
        context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTING_MODE, mode.name)
            .apply()
    }

    fun saveAllowedApps(context: Context, packages: Set<String>) {
        context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ALLOWED_APPS, packages.toSet())
            .apply()
    }

    fun saveDisallowedApps(context: Context, packages: Set<String>) {
        context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DISALLOWED_APPS, packages.toSet())
            .apply()
    }

    fun saveLastProxyConfig(context: Context, config: ProxyConfig) {
        context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PROTOCOL, config.protocol.name)
            .putString(KEY_LAST_HOST, config.host.trim())
            .putInt(KEY_LAST_PORT, config.port)
            .putString(KEY_LAST_USERNAME, config.username)
            .putString(KEY_LAST_PASSWORD, config.password)
            .putString(KEY_LAST_LABEL, config.label)
            .apply()
    }

    fun loadLastProxyConfig(context: Context): ProxyConfig? {
        val prefs = context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_LAST_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_LAST_PORT, 0)
        if (host.isEmpty() || port !in 1..65535) return null
        return ProxyConfig(
            protocol = ProxyProtocol.fromString(prefs.getString(KEY_LAST_PROTOCOL, null)),
            host = host,
            port = port,
            username = prefs.getString(KEY_LAST_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_LAST_PASSWORD, "").orEmpty(),
            label = prefs.getString(KEY_LAST_LABEL, "").orEmpty()
        )
    }
}
