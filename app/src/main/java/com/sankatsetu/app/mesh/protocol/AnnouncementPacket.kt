package com.sankatsetu.app.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * Signed presence beacon. TLV-encoded payload carried inside a
 * [MessageType.ANNOUNCE] packet. Format ported from Bitchat's
 * `AnnouncementPacket` (public domain).
 *
 * Sent every 4s while isolated, backing off to 15-30s jittered once
 * connected (see [com.sankatsetu.app.mesh.router.MessageRouter]).
 *
 * Publishing [noisePublicKey] and [signingPublicKey] in cleartext here is a
 * known metadata leak inherited from Bitchat's design — see
 * docs/concepts/ble-mesh-protocol.md#metadata-leakage. It's what lets a peer
 * recognize you across reconnects without a server, at the cost of a passive
 * listener being able to do the same.
 */
data class AnnouncementPacket(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    /** Up to 10 peer IDs this node currently has a live link to (topology hint, 60s freshness). */
    val directNeighbors: List<ByteArray> = emptyList(),
    // Nullable, cached-only: see ChatViewModel.sendAnnounce's own doc for why
    // this is never a fresh GPS request (announces fire every 4-30s, far too
    // often to block on live location) -- a peer only shows up on the map if
    // this device already has a recent fix cached from some other real use
    // (opening the Map tab, sending an SOS). See docs/adr/0022-peer-location.md.
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    private enum class Tlv(val id: Byte) {
        NICKNAME(0x01), NOISE_KEY(0x02), SIGNING_KEY(0x03), NEIGHBORS(0x04), LATITUDE(0x05), LONGITUDE(0x06)
    }

    fun encode(): ByteArray? {
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        if (nicknameBytes.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) return null

        val out = ByteArrayOutputStream()
        writeTlv(out, Tlv.NICKNAME.id, nicknameBytes)
        writeTlv(out, Tlv.NOISE_KEY.id, noisePublicKey)
        writeTlv(out, Tlv.SIGNING_KEY.id, signingPublicKey)

        if (directNeighbors.isNotEmpty()) {
            val capped = directNeighbors.take(10)
            val neighborBytes = ByteArrayOutputStream()
            for (n in capped) neighborBytes.write(if (n.size == 8) n else n.copyOf(8))
            val bytes = neighborBytes.toByteArray()
            if (bytes.size <= 255) writeTlv(out, Tlv.NEIGHBORS.id, bytes)
        }
        if (latitude != null && longitude != null) {
            writeTlv(out, Tlv.LATITUDE.id, longBytes(latitude.toRawBits()))
            writeTlv(out, Tlv.LONGITUDE.id, longBytes(longitude.toRawBits()))
        }
        return out.toByteArray()
    }

    private fun longBytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0..7) bytes[7 - i] = ((value ushr (i * 8)) and 0xFF).toByte()
        return bytes
    }

    companion object {
        private fun writeTlv(out: ByteArrayOutputStream, type: Byte, value: ByteArray) {
            out.write(type.toInt())
            out.write(value.size)
            out.write(value)
        }

        private fun readLong(bytes: ByteArray): Long {
            var result = 0L
            for (b in bytes) result = (result shl 8) or (b.toLong() and 0xFF)
            return result
        }

        fun decode(data: ByteArray): AnnouncementPacket? {
            var offset = 0
            var nickname: String? = null
            var noiseKey: ByteArray? = null
            var signingKey: ByteArray? = null
            var neighbors: List<ByteArray> = emptyList()
            var latitude: Double? = null
            var longitude: Double? = null

            while (offset + 2 <= data.size) {
                val type = data[offset]; offset += 1
                val length = data[offset].toInt() and 0xFF; offset += 1
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    Tlv.NICKNAME.id -> nickname = String(value, Charsets.UTF_8)
                    Tlv.NOISE_KEY.id -> noiseKey = value
                    Tlv.SIGNING_KEY.id -> signingKey = value
                    Tlv.NEIGHBORS.id -> {
                        if (length > 0 && length % 8 == 0) {
                            neighbors = (0 until length / 8).map { i -> value.copyOfRange(i * 8, i * 8 + 8) }
                        }
                    }
                    Tlv.LATITUDE.id -> { if (length == 8) latitude = Double.fromBits(readLong(value)) }
                    Tlv.LONGITUDE.id -> { if (length == 8) longitude = Double.fromBits(readLong(value)) }
                    else -> Unit // unknown TLV — skip, forward-compatible
                }
            }
            if (nickname == null || noiseKey == null || signingKey == null) return null
            return AnnouncementPacket(nickname, noiseKey, signingKey, neighbors, latitude, longitude)
        }
    }
}
