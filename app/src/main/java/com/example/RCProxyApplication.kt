package com.example

import android.app.Application
import com.example.vpn.LogLevel
import com.example.vpn.TunnelStateHolder

class RCProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
        val previousCrash = prefs.getString(KEY_LAST_CRASH, null)
        if (!previousCrash.isNullOrBlank()) {
            TunnelStateHolder.log(LogLevel.ERROR, "Last crash: $previousCrash")
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val summary = "${throwable::class.java.simpleName}: ${throwable.message ?: "unknown"} (${thread.name})"
                prefs.edit().putString(KEY_LAST_CRASH, summary).commit()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val CRASH_PREFS = "rcproxy_crash_prefs"
        private const val KEY_LAST_CRASH = "last_crash"
    }
}
