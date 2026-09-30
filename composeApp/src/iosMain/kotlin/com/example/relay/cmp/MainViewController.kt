package com.example.relay.cmp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry for the same Kotlin Compose UI as Android-style Relay.
 * Built only on macOS: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    // Point at PC Gateway on the LAN (override in Xcode scheme env later if needed).
    RelaySharedApp(gatewayHostOverride = null, gatewayPortOverride = 8080)
}
