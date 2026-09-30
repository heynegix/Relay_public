package com.example.relay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle

@Composable
actual fun PlatformMapView(
    modifier: Modifier,
    styleUri: String,
) {
    MaplibreMap(
        modifier = modifier,
        baseStyle = BaseStyle.Uri(styleUri),
    )
}

actual val isPlatformMapAvailable: Boolean = true
