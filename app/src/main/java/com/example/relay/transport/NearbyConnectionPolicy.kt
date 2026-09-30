package com.example.relay.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Admission modes for Nearby (BLE / Wi-Fi Direct) peer connections. */
enum class NearbyConnectionMode {
    /**
     * Disaster default: accept every reachable peer so a relay mesh forms with
     * zero operator action. Application payloads are still validated, size
     * limited, deduplicated and marked unverified downstream.
     */
    OPEN,

    /**
     * Locked-down: only peers on an explicit, out-of-band provisioned allow-list
     * may connect. Proximity alone never grants trust.
     */
    TRUSTED,
}

/**
 * Decides whether the Nearby transport may connect to a peer.
 *
 * Radio proximity is not identity: any device can advertise an arbitrary
 * endpoint name. [NearbyConnectionMode.OPEN] is the hands-off disaster default;
 * [NearbyConnectionMode.TRUSTED] gates every inbound and outbound connection on
 * an allow-list an operator provisions out of band. Switching to TRUSTED never
 * retroactively trusts a peer that merely happens to be nearby.
 */
class NearbyConnectionPolicy(
    initialMode: NearbyConnectionMode = NearbyConnectionMode.OPEN,
    trustedPeers: Collection<String> = emptySet(),
    private val onTrustedPeersChanged: (Set<String>) -> Unit = {},
) {
    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<NearbyConnectionMode> = _mode

    private val trusted = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(trustedPeers.filter { it.isNotBlank() })
    }

    /** Snapshot of the current trusted peer identities. */
    val trustedPeers: Set<String> get() = trusted.toSet()

    fun setMode(mode: NearbyConnectionMode) {
        _mode.value = mode
    }

    fun trust(peerId: String) {
        if (peerId.isNotBlank() && trusted.add(peerId)) onTrustedPeersChanged(trusted.toSet())
    }

    fun revoke(peerId: String) {
        if (trusted.remove(peerId)) onTrustedPeersChanged(trusted.toSet())
    }

    /** True when a connection to [peerId] is permitted under the current mode. */
    fun allowsConnection(peerId: String): Boolean = when (_mode.value) {
        NearbyConnectionMode.OPEN -> true
        NearbyConnectionMode.TRUSTED -> peerId in trusted
    }
}
