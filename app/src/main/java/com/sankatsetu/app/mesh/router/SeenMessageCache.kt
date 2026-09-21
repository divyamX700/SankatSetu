package com.sankatsetu.app.mesh.router

import com.sankatsetu.app.mesh.protocol.MeshPacket
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Bounded, time-expiring dedup cache for relay flood control.
 *
 * Parameters match Bitchat's `TransportConfig` exactly (1000 entries, 5-minute
 * expiry — see docs/concepts/ble-mesh-protocol.md#flood-control): a packet is
 * "the same packet" if (senderId, timestamp, type, digest-of-payload) all
 * match, which is what lets two different relays' copies of one broadcast
 * collapse into a single delivery without a global message ID scheme.
 */
class SeenMessageCache(
    private val maxEntries: Int = 1000,
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    // LinkedHashMap in insertion order gives us free FIFO eviction once we're
    // over capacity — we don't need access-order/LRU semantics here since
    // expiry is time-based, not access-based.
    private val seen = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(maxEntries + 1, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > maxEntries
        }
    )

    /**
     * Returns true and records the packet if it hasn't been seen (within
     * [ttlMillis]); returns false if it's a duplicate. Callers should drop
     * the packet — and cancel any scheduled relay of it — on false.
     */
    fun markIfNew(packet: MeshPacket): Boolean {
        val key = keyFor(packet)
        synchronized(seen) {
            val existingAt = seen[key]
            val nowMs = now()
            if (existingAt != null && nowMs - existingAt < ttlMillis) return false
            seen[key] = nowMs
            return true
        }
    }

    private fun keyFor(packet: MeshPacket): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packet.payload)
            .copyOfRange(0, 8)
        return buildString {
            append(packet.senderId.joinToString("") { "%02x".format(it) })
            append(':').append(packet.timestamp)
            append(':').append(packet.type.value)
            append(':').append(digest.joinToString("") { "%02x".format(it) })
        }
    }
}
