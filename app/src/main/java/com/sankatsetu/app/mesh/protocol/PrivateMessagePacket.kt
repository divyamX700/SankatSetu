package com.sankatsetu.app.mesh.protocol

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Payload carried inside a [MessageType.NOISE_ENCRYPTED] frame once the
 * Noise session decrypts it — i.e. this struct is never seen by a relay,
 * only by the two endpoints of a live session. Ported from Bitchat's
 * `PrivateMessagePacket` (public domain).
 */
data class PrivateMessagePacket(
    val messageId: String = UUID.randomUUID().toString(),
    val content: String
) {
    private enum class Tlv(val id: Byte) { MESSAGE_ID(0x00), CONTENT(0x01) }

    fun encode(): ByteArray? {
        val idBytes = messageId.toByteArray(Charsets.UTF_8)
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        if (idBytes.size > 255 || contentBytes.size > 255) return null

        val out = ByteArrayOutputStream()
        out.write(Tlv.MESSAGE_ID.id.toInt()); out.write(idBytes.size); out.write(idBytes)
        out.write(Tlv.CONTENT.id.toInt()); out.write(contentBytes.size); out.write(contentBytes)
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): PrivateMessagePacket? {
            var offset = 0
            var messageId: String? = null
            var content: String? = null

            while (offset + 2 <= data.size) {
                val type = data[offset]; offset += 1
                val length = data[offset].toInt() and 0xFF; offset += 1
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    Tlv.MESSAGE_ID.id -> messageId = String(value, Charsets.UTF_8)
                    Tlv.CONTENT.id -> content = String(value, Charsets.UTF_8)
                    else -> return null // unlike announce, an unknown TLV here is a hard decode failure
                }
            }
            if (messageId == null || content == null) return null
            return PrivateMessagePacket(messageId, content)
        }
    }
}
