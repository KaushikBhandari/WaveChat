package com.example.wavechat

/**
 * Represents a discovered BLE peer in the mesh.
 *
 * @param name      Human-readable label shown in the UI.
 * @param address   BLE MAC address — used as unique peer ID.
 * @param rssi      Signal strength (dBm). Stronger = closer = prefer as relay.
 * @param lastSeen  System ms when this peer was last detected in a scan.
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int = -100,
    val lastSeen: Long = System.currentTimeMillis()
) {
    /** Peer is considered active if seen within the last 30 seconds. */
    fun isActive(): Boolean =
        (System.currentTimeMillis() - lastSeen) < 30_000
}
