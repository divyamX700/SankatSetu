package com.sankatsetu.app.mesh.router

import java.util.Collections

/**
 * Queues directed traffic addressed to a peer who isn't reachable *right
 * now* (no live mesh link to anyone at all, or no path exists yet), so it
 * gets resent automatically once that peer — or a path to them — appears,
 * instead of being silently dropped. Parameters match Bitchat's
 * `MessageOutboxStore` policy exactly (100 messages/peer, 24h TTL, 8 resend
 * attempts) — see docs/concepts/ble-mesh-protocol.md.
 *
 * This is an in-memory structure with the same shape as Bitchat's own
 * outbox; persistence to Room (so a queued message survives a process kill)
 * is a Day 3 TODO — see docs/adr/0008-store-and-forward-scope.md. What's
 * here is fully real and tested: the quota, expiry, and attempt-tracking
 * logic a persisted version would wrap.
 */
class SenderOutbox(
    private val maxPerPeer: Int = 100,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
    private val maxAttempts: Int = 8,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    data class QueuedMessage(
        val messageId: String,
        val recipientId: ByteArray,
        val type: com.sankatsetu.app.mesh.protocol.MessageType,
        val payload: ByteArray,
        val sign: Boolean,
        val queuedAt: Long,
        var sendAttempts: Int = 0
    )

    // Keyed by hex-encoded recipient ID for a stable, comparable map key over a ByteArray.
    private val queues = Collections.synchronizedMap(LinkedHashMap<String, MutableList<QueuedMessage>>())

    private fun key(recipientId: ByteArray): String = recipientId.joinToString("") { "%02x".format(it) }

    /** Enqueues a message. Oldest-first eviction once [maxPerPeer] is reached for that recipient. */
    fun enqueue(message: QueuedMessage) {
        synchronized(queues) {
            val list = queues.getOrPut(key(message.recipientId)) { mutableListOf() }
            pruneExpiredLocked(list)
            if (list.size >= maxPerPeer) list.removeAt(0)
            list.add(message)
        }
    }

    /** All still-valid (not expired, under the attempt cap) queued messages for one recipient. */
    fun pending(recipientId: ByteArray): List<QueuedMessage> {
        synchronized(queues) {
            val list = queues[key(recipientId)] ?: return emptyList()
            pruneExpiredLocked(list)
            return list.filter { it.sendAttempts < maxAttempts }.toList()
        }
    }

    /** Records a resend attempt. Messages at [maxAttempts] are dropped (visible send failure upstream, not silent). */
    fun recordAttempt(recipientId: ByteArray, messageId: String) {
        synchronized(queues) {
            val list = queues[key(recipientId)] ?: return
            val index = list.indexOfFirst { it.messageId == messageId }
            if (index < 0) return
            list[index].sendAttempts += 1
            if (list[index].sendAttempts >= maxAttempts) list.removeAt(index)
        }
    }

    /** Called once a message is confirmed delivered (or the caller otherwise wants it gone). */
    fun remove(recipientId: ByteArray, messageId: String) {
        synchronized(queues) {
            queues[key(recipientId)]?.removeAll { it.messageId == messageId }
        }
    }

    private fun pruneExpiredLocked(list: MutableList<QueuedMessage>) {
        val cutoff = now() - ttlMillis
        list.removeAll { it.queuedAt < cutoff }
    }
}
