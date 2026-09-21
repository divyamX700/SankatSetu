package com.sankatsetu.app.mesh.protocol

/**
 * Wire-level packet types for the Sankat Setu mesh protocol.
 *
 * Numeric values and the overall type inventory are ported from Bitchat's
 * `MessageType.swift` (public domain / Unlicense — see NOTICE.md), with our
 * own additions for structured SOS reports and mesh IOU vouchers appended
 * after the range Bitchat has ever allocated, so a real Bitchat client and
 * ours can never collide on a type byte even though we don't interoperate.
 */
enum class MessageType(val value: Byte) {
    // Public, unencrypted
    ANNOUNCE(0x01),
    MESSAGE(0x02),          // public chat broadcast
    LEAVE(0x03),
    COURIER_ENVELOPE(0x04), // store-and-forward mail carried by a peer (Day 2)
    REQUEST_SYNC(0x21),     // gossip sync (Day 2)

    // Noise-encrypted channel
    NOISE_HANDSHAKE(0x10),
    NOISE_ENCRYPTED(0x11),

    // Fragmentation (Day 2)
    FRAGMENT(0x20),

    // Mesh diagnostics
    PING(0x26),
    PONG(0x27),

    // Sankat Setu additions (Day 2-3) — outside Bitchat's allocated range
    SOS_BROADCAST(0x40),
    IOU_ENVELOPE(0x41),
    IOU_SETTLEMENT_ACK(0x42);

    companion object {
        private val byValue = entries.associateBy { it.value }
        fun fromByte(b: Byte): MessageType? = byValue[b]
    }
}
