package com.example.relay.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * iOS placeholder: MapLibre Native is not yet linked.
 * Re-enable when Kotlin SPM import supports the current toolchain (requires 2.4.20+)
 * or when the third-party spm4kmp plugin is verified with Kotlin 2.2.21.
 */
@Composable
actual fun PlatformMapView(
    modifier: Modifier,
    styleUri: String,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("地図を準備中（iOS版は今後対応予定）")
    }
}

actual val isPlatformMapAvailable: Boolean = false
