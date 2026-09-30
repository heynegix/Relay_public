package com.example.relay.cmp

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Relay (Compose Multiplatform)") {
        // Localhost override for PC Gateway on same machine (Windows/macOS/Linux desktop).
        RelaySharedApp(gatewayHostOverride = "127.0.0.1", gatewayPortOverride = 8080)
    }
}
