package com.sankatsetu.app.mesh.router

import com.sankatsetu.app.mesh.protocol.FragmentPacket
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Reassembles [FragmentPacket] chunks back into the original fully-encoded
 * packet bytes. Parameters match the whitepaper table in
 * docs/concepts/ble-mesh-protocol.md: 128 concurrent assemblies, 30-second
 * timeout per fragment ID, 1 MiB total reassembly cap.
 */
class FragmentAssembler(
    private val maxConcurrent: Int = 128,
    private val timeoutMillis: Long = 30_000L,
    private val maxTotalBytes: Int = 1 * 1024 * 1024,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private class Assembly(val total: Int, val startedAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var receivedCount = 0
        var receivedBytes = 0
    }

    // Insertion-order eviction once over maxConcurrent, same rationale as SeenMessageCache.
    private val assemblies = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Assembly>(maxConcurrent + 1, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Assembly>?): Boolean =
                size > maxConcurrent
        }
    )

    /**
     * Feeds one fragment in. Returns the fully reassembled byte blob once
     * every fragment for its ID has arrived, else null. Expired assemblies
     * (per [timeoutMillis]) are dropped on next access, not proactively swept
     * — fine for a mesh whose fragment volume is bounded by [maxConcurrent]
     * anyway.
     */
    fun addFragment(fragment: FragmentPacket): ByteArray? {
        synchronized(assemblies) {
            val existing = assemblies[fragment.fragmentId]
            val nowMs = now()

            val assembly = if (existing == null || nowMs - existing.startedAt > timeoutMillis) {
                Assembly(fragment.total, nowMs).also { assemblies[fragment.fragmentId] = it }
            } else {
                existing
            }

            if (fragment.total != assembly.total) return null // inconsistent total — drop rather than corrupt
            if (fragment.index >= assembly.total) return null
            if (assembly.chunks[fragment.index] != null) return null // duplicate fragment, already counted

            if (assembly.receivedBytes + fragment.chunk.size > maxTotalBytes) {
                assemblies.remove(fragment.fragmentId) // one oversized assembly shouldn't linger
                return null
            }

            assembly.chunks[fragment.index] = fragment.chunk
            assembly.receivedCount += 1
            assembly.receivedBytes += fragment.chunk.size

            if (assembly.receivedCount < assembly.total) return null

            assemblies.remove(fragment.fragmentId)
            val out = ByteArray(assembly.receivedBytes)
            var offset = 0
            for (chunk in assembly.chunks) {
                chunk!!.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        }
    }
}
