package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.security.AppIntegrityGuard
import com.example.ui.NetRelayApp
import com.example.ui.theme.NetRelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppIntegrityGuard.enforceOrCrash(this)
        enableEdgeToEdge()
        setContent {
            NetRelayTheme {
                NetRelayApp()
            }
        }
    }
}
