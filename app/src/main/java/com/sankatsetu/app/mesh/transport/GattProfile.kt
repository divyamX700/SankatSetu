package com.sankatsetu.app.mesh.transport

import java.util.UUID

/**
 * BLE GATT profile for the Sankat Setu mesh. Deliberately a distinct UUID
 * from Bitchat's own service — we implement the same *protocol* (packet
 * format, TTL/dedup/jitter behaviour) but are not wire-compatible with real
 * Bitchat clients, and don't want to advertise as one.
 *
 * One characteristic carries all mesh traffic in both directions:
 * - A central writes an outbound [com.sankatsetu.app.mesh.protocol.MeshPacket]
 *   (binary-encoded) to this characteristic on the peripheral it's connected to.
 * - The peripheral notifies connected centrals with inbound traffic on the
 *   same characteristic.
 *
 * This means every BLE connection is one bidirectional pipe regardless of
 * which side is GATT-central vs GATT-peripheral, which is what
 * [MeshTransport] relies on when it wraps each connection as a single
 * [com.sankatsetu.app.mesh.router.MeshLink].
 */
object GattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("7a1e9e40-9c1a-4f2e-8b3a-6d2f3c9e1b01")
    val MESH_CHARACTERISTIC_UUID: UUID = UUID.fromString("7a1e9e41-9c1a-4f2e-8b3a-6d2f3c9e1b01")
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Android's default BLE ATT payload is small; we request a larger MTU on
     * connect (see [MeshTransport]) but must still tolerate the platform
     * refusing it. [com.sankatsetu.app.mesh.protocol.BinaryProtocol] frames
     * are typically well under this even unfragmented for chat-sized
     * messages; proper fragmentation for larger payloads is Day 2 scope
     * (docs/adr/0002).
     */
    const val REQUESTED_MTU = 517
    const val DEFAULT_ATT_PAYLOAD = 20 // pre-negotiation worst case (23-byte ATT_MTU minus 3-byte header)
}
