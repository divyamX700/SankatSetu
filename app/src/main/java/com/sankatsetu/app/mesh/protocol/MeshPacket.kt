package com.sankatsetu.app.mesh.protocol

/**
 * The core packet structure for every Sankat Setu mesh message.
 *
 * Field layout mirrors Bitchat's `BitchatPacket` (public domain — see
 * NOTICE.md): [version][type][ttl][timestamp:8][flags][length:2][senderID:8]
 * [recipientID:8?][payload][sigLen:1?][signature:variable?], encoded/decoded
 * by [BinaryProtocol]. The signature is length-prefixed rather than
 * Bitchat's fixed 64 bytes because we sign with ECDSA/P-256 (variable-length
 * DER encoding), not Ed25519 — see docs/adr/0007-ecdsa-not-ed25519.md. We
 * also deliberately did not port Bitchat's v2 source-routing fields
 * (`route`, `isRSR`) or its zlib payload compression — out of scope for a
 * 4-day build; see docs/adr/0002.
 *
 * @param ttl Hop budget. Starts at [DEFAULT_TTL] on origin, decremented by
 *   one on every relay. A packet with ttl == 0 is never relayed further.
 * @param senderId First 8 bytes of SHA-256(sender's Noise static public key).
 *   Stable across sessions — see the "known limitation" note in
 *   docs/concepts/ble-mesh-protocol.md#metadata-leakage.
 * @param recipientId Null for broadcast/public packets. Present for directed
 *   traffic (handshakes, private messages, IOU envelopes, SOS acks).
 */
data class MeshPacket(
    val version: Byte = 1,
    val type: MessageType,
    val ttl: Byte,
    val timestamp: Long,
    val senderId: ByteArray,
    val recipientId: ByteArray? = null,
    val payload: ByteArray,
    val signature: ByteArray? = null
) {
    companion object {
        const val DEFAULT_TTL: Byte = 7
        const val SENDER_ID_SIZE = 8
        const val RECIPIENT_ID_SIZE = 8
        // No fixed SIGNATURE_SIZE: ECDSA/P-256 signatures are variable-length
        // DER, length-prefixed on the wire instead — see BinaryProtocol.kt.
    }

    /** Returns a copy with ttl decremented by one, floored at 0. */
    fun decremented(): MeshPacket = copy(ttl = (ttl - 1).coerceAtLeast(0).toByte())

    /**
     * The bytes that get signed / verified. Notably excludes [ttl] (which
     * mutates on every relay hop) and [signature] itself, and pins ttl=0 for
     * the signature computation so relaying never invalidates it — same
     * rationale as Bitchat's `toBinaryDataForSigning()`.
     */
    fun signingBytes(): ByteArray =
        BinaryProtocol.encode(copy(ttl = 0, signature = null), padding = false)

    // ByteArray fields need structural equality, not reference equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return version == other.version &&
            type == other.type &&
            ttl == other.ttl &&
            timestamp == other.timestamp &&
            senderId.contentEquals(other.senderId) &&
            (recipientId?.contentEquals(other.recipientId ?: ByteArray(0)) ?: (other.recipientId == null)) &&
            payload.contentEquals(other.payload) &&
            (signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null))
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderId.contentHashCode()
        result = 31 * result + (recipientId?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}
