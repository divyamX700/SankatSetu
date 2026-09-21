package com.sankatsetu.app.mesh.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary wire codec for [MeshPacket]. Ported from Bitchat's
 * `BinaryProtocol.swift` (public domain — see NOTICE.md), with compression
 * and v2 source-routing removed (out of scope for this build).
 *
 * Wire format, all multi-byte fields big-endian (network byte order):
 *
 * ```
 * +---------+------+-----+-----------+-------+--------+
 * | version | type | ttl | timestamp | flags | length |
 * | 1 byte  |1 byte|1byte|  8 bytes  |1 byte | 2 bytes|
 * +---------+------+-----+-----------+-------+--------+
 * +----------+--------------+---------+-------+-----------+
 * | senderID | recipientID* | payload | sigLen| signature*|
 * | 8 bytes  |   8 bytes    |variable | 1 byte| variable  |
 * +----------+--------------+---------+-------+-----------+
 * ```
 * `*` optional, presence indicated by flag bits.
 *
 * Header size is fixed at 14 bytes (version+type+ttl+timestamp+flags+length),
 * matching Bitchat's v1 wire format — with one deliberate deviation: Bitchat
 * assumes a fixed 64-byte Ed25519 signature and has no length prefix for it.
 * We sign with ECDSA/P-256 instead (see docs/adr/0007-ecdsa-not-ed25519.md),
 * whose DER-encoded signatures vary roughly 68-72 bytes, so the signature
 * field carries an explicit 1-byte length instead of being fixed-size.
 */
object BinaryProtocol {
    const val HEADER_SIZE = 14
    private const val FLAG_HAS_RECIPIENT: Int = 0x01
    private const val FLAG_HAS_SIGNATURE: Int = 0x02

    /**
     * @param padding When true (default for anything Noise-encrypted), pads
     *   the frame to the next bucket via [MessagePadding]. Public/broadcast
     *   traffic should pass `padding = false` — its length is observable
     *   either way, and padding it just wastes airtime.
     */
    fun encode(packet: MeshPacket, padding: Boolean = false): ByteArray {
        val payload = packet.payload
        require(payload.size <= UShort.MAX_VALUE.toInt()) { "payload too large for v1 length field" }

        val signature = packet.signature
        require(signature == null || signature.size <= 255) { "signature too large for 1-byte length prefix" }

        val out = ByteArrayOutputStream(
            HEADER_SIZE + MeshPacket.SENDER_ID_SIZE +
                (if (packet.recipientId != null) MeshPacket.RECIPIENT_ID_SIZE else 0) +
                payload.size +
                (if (signature != null) 1 + signature.size else 0) +
                16 // slack for padding decision
        )

        out.write(packet.version.toInt())
        out.write(packet.type.value.toInt())
        out.write(packet.ttl.toInt())

        // timestamp: 8 bytes big-endian
        val ts = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(packet.timestamp).array()
        out.write(ts)

        var flags = 0
        if (packet.recipientId != null) flags = flags or FLAG_HAS_RECIPIENT
        if (packet.signature != null) flags = flags or FLAG_HAS_SIGNATURE
        out.write(flags)

        val length = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).array()
        out.write(length)

        out.write(fixedSize(packet.senderId, MeshPacket.SENDER_ID_SIZE))
        packet.recipientId?.let { out.write(fixedSize(it, MeshPacket.RECIPIENT_ID_SIZE)) }

        out.write(payload)
        signature?.let {
            out.write(it.size)
            out.write(it)
        }

        val raw = out.toByteArray()
        if (!padding) return raw
        return MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
    }

    /** Attempts decode as-is first (robust when padding wasn't applied), then with padding stripped. */
    fun decode(data: ByteArray): MeshPacket? {
        decodeCore(data)?.let { return it }
        val unpadded = MessagePadding.unpad(data)
        if (unpadded.contentEquals(data)) return null
        return decodeCore(unpadded)
    }

    private fun decodeCore(raw: ByteArray): MeshPacket? {
        if (raw.size < HEADER_SIZE + MeshPacket.SENDER_ID_SIZE) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)

        val version = buf.get()
        if (version != 1.toByte()) return null

        val typeByte = buf.get()
        val type = MessageType.fromByte(typeByte) ?: return null
        val ttl = buf.get()

        val timestamp = buf.long
        val flags = buf.get().toInt() and 0xFF
        val hasRecipient = (flags and FLAG_HAS_RECIPIENT) != 0
        val hasSignature = (flags and FLAG_HAS_SIGNATURE) != 0

        val length = buf.short.toInt() and 0xFFFF

        if (buf.remaining() < MeshPacket.SENDER_ID_SIZE) return null
        val senderId = ByteArray(MeshPacket.SENDER_ID_SIZE).also { buf.get(it) }

        var recipientId: ByteArray? = null
        if (hasRecipient) {
            if (buf.remaining() < MeshPacket.RECIPIENT_ID_SIZE) return null
            recipientId = ByteArray(MeshPacket.RECIPIENT_ID_SIZE).also { buf.get(it) }
        }

        if (buf.remaining() < length) return null
        val payload = ByteArray(length).also { buf.get(it) }

        var signature: ByteArray? = null
        if (hasSignature) {
            if (buf.remaining() < 1) return null
            val sigLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < sigLen) return null
            signature = ByteArray(sigLen).also { buf.get(it) }
        }

        return MeshPacket(
            version = version,
            type = type,
            ttl = ttl,
            timestamp = timestamp,
            senderId = senderId,
            recipientId = recipientId,
            payload = payload,
            signature = signature
        )
    }

    private fun fixedSize(bytes: ByteArray, size: Int): ByteArray {
        if (bytes.size == size) return bytes
        val result = ByteArray(size)
        System.arraycopy(bytes, 0, result, 0, minOf(bytes.size, size))
        return result
    }
}
