package com.sankatsetu.app.mesh.router

import com.sankatsetu.app.mesh.protocol.MessageType
import java.security.MessageDigest

/**
 * Decides which subset of currently-connected links a broadcast packet
 * relays down. Simplified port of Bitchat's `BLEFanoutSelector.swift`
 * (public domain) — we collapsed its dual peripheral/central link-dedup
 * machinery (iOS's BLE stack can hold two simultaneous links to one peer;
 * our Android transport keeps exactly one GATT connection per peer, so that
 * whole layer doesn't apply here) down to "select ~log2(n) of n known peer
 * links, deterministically, so peers observing the same broadcast tend to
 * pick different relay subsets and duplicate coverage stays high without
 * every peer relaying to every link."
 *
 * [ANNOUNCE], [FRAGMENT], and [REQUEST_SYNC] packets always get full fanout
 * (every non-ingress link) — see [shouldSubset]. Directed traffic (anything
 * with a recipientId) bypasses this selector entirely and is handled by
 * [MessageRouter] as a single deterministic relay toward the known link, not
 * a fanout.
 */
object FanoutSelector {

    /** True if broadcasting [type] should go through subset selection at all. */
    fun shouldSubset(type: MessageType): Boolean =
        type != MessageType.FRAGMENT && type != MessageType.ANNOUNCE && type != MessageType.REQUEST_SYNC

    /** ⌈log2(count)⌉, clamped so small neighbourhoods get full fanout instead of a fraction of nothing. */
    fun subsetSize(count: Int): Int {
        if (count <= 0) return 0
        if (count <= 2) return count
        var value = count - 1
        var bits = 0
        while (value > 0) {
            value = value shr 1
            bits += 1
        }
        return minOf(count, maxOf(1, bits + 1))
    }

    /**
     * Deterministic subset: score every candidate link ID by
     * SHA-256(seed + "::" + linkId), sort ascending, take the first [k].
     * Deterministic-per-messageId (not per-node) is the point: two different
     * relays flooding the *same* message tend to fan out to *different*
     * next-hop subsets, which is what makes partial coverage still add up to
     * full coverage across the mesh instead of everyone picking the same k
     * neighbours and leaving the rest permanently unreached.
     */
    fun deterministicSubset(linkIds: List<String>, k: Int, seed: String): Set<String> {
        if (k <= 0 || linkIds.size <= k) return linkIds.toSet()
        val digest = MessageDigest.getInstance("SHA-256")
        val scored = linkIds.map { id ->
            val hash = digest.digest("$seed::$id".toByteArray(Charsets.UTF_8))
            Triple(hash, id, id)
        }.sortedWith(
            Comparator<Triple<ByteArray, String, String>> { a, b -> compareBytes(a.first, b.first) }
                .thenBy { it.second }
        )
        return scored.take(k).map { it.second }.toSet()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}
