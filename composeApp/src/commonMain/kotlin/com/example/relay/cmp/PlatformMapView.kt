package com.example.relay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific map composable.
 *
 * On Android and Desktop, this renders an interactive MapLibre map.
 * On iOS, MapLibre Native is not yet linked (SPM plugin requires Kotlin 2.4.20+),
 * so a placeholder is shown instead. The native implementation can be enabled when supported.
 */
@Composable
expect fun PlatformMapView(
    modifier: Modifier,
    styleUri: String,
)

/**
 * Whether the platform has a working map renderer.
 * iOS returns false until MapLibre Native is integrated via SPM.
 */
expect val isPlatformMapAvailable: Boolean
