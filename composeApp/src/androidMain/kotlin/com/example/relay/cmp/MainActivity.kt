package com.example.relay.cmp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Optional Android host for the shared Compose UI (production Android remains :app). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RelaySharedApp(gatewayHostOverride = "10.0.2.2", gatewayPortOverride = 8080)
        }
    }
}
