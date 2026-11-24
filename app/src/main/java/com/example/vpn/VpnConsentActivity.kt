package com.example.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import com.example.data.AppPreferences
import com.example.security.AppIntegrityGuard

/**
 * Translucent headless activity used only when the Floating Bubble is tapped before Android's
 * one-time VPN permission consent was granted. Pops only the system VPN dialog without opening MainActivity.
 */
class VpnConsentActivity : Activity() {

    companion object {
        private const val REQ_VPN_PREPARE = 4001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppIntegrityGuard.enforceOrCrash(this)

        val prepareIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prepareIntent == null) {
            startSavedProxyAndFinish()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(prepareIntent, REQ_VPN_PREPARE)
        }
    }

    @Deprecated("Required for lightweight Activity without ComponentActivity overhead")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE) {
            if (resultCode == RESULT_OK) {
                startSavedProxyAndFinish()
            } else {
                Toast.makeText(this, "VPN permission cancelled", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun startSavedProxyAndFinish() {
        val saved = AppPreferences.loadLastProxyConfig(this)
        if (saved != null) {
            ProxyVpnService.startConnect(this, saved)
        }
        finish()
    }
}
