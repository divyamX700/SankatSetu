package com.sankatsetu.app.mesh.protocol

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * A category the sender picked one-handed under stress — see
 * docs/TODO.md's SOS ideation for why this is a fixed picker, never free
 * text: it must never route through the LLM, and must work instantly with
 * no model side-loaded, unlike the Assistant's own generation path.
 */
enum class SosCategory(val wireValue: Byte, val label: String) {
    MEDICAL(0x01, "Medical"),
    TRAPPED(0x02, "Trapped, can't move"),
    FIRE(0x03, "Fire"),
    FLOOD(0x04, "Flood water"),
    OTHER(0x05, "Other emergency");

    companion object {
        fun fromWire(b: Byte): SosCategory? = entries.find { it.wireValue == b }
    }
}

/**
 * Carried as the payload of a [MessageType.SOS_BROADCAST] packet — see
 * docs/TODO.md's SOS ideation for the full reasoning. Deliberately
 * unsigned and un-Noise-encrypted, unlike [IouPacket]: this is a flooded
 * broadcast meant for every phone in range including strangers with no
 * handshake ever completed, not a directed private message, so there is no
 * counterparty key to sign against and nothing here is meant to stay
 * confidential. [MeshPacket.senderId]/`ttl` already carry who sent it and
 * how many hops away, so this payload only needs what's genuinely new:
 * the category and when it was raised.
 */
data class SosPacket(
    val sosId: String = UUID.randomUUID().toString(),
    val category: SosCategory,
    val createdAt: Long = System.currentTimeMillis(),
    // Nullable, not required: [com.sankatsetu.app.mesh.emergency.SosManager]
    // only waits a few seconds for a GPS/network fix before broadcasting
    // regardless, since blocking an emergency send on a slow or unavailable
    // location would be worse than sending without one. Both TLVs are
    // written together or not at all -- there's no real case for one
    // without the other.
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    private enum class Tlv(val id: Byte) {
        SOS_ID(0x00), CATEGORY(0x01), CREATED_AT(0x02), LATITUDE(0x03), LONGITUDE(0x04)
    }

    fun encode(): ByteArray? {
        val idBytes = sosId.toByteArray(Charsets.UTF_8)
        if (idBytes.size > 255) return null

        val out = ByteArrayOutputStream()
        out.write(Tlv.SOS_ID.id.toInt()); out.write(idBytes.size); out.write(idBytes)
        out.write(Tlv.CATEGORY.id.toInt()); out.write(1); out.write(category.wireValue.toInt())
        out.write(Tlv.CREATED_AT.id.toInt()); out.write(8); writeLong(out, createdAt)
        if (latitude != null && longitude != null) {
            out.write(Tlv.LATITUDE.id.toInt()); out.write(8); writeLong(out, latitude.toRawBits())
            out.write(Tlv.LONGITUDE.id.toInt()); out.write(8); writeLong(out, longitude.toRawBits())
        }
        return out.toByteArray()
    }

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
    }

    companion object {
        private fun readLong(bytes: ByteArray): Long {
            var result = 0L
            for (b in bytes) result = (result shl 8) or (b.toLong() and 0xFF)
            return result
        }

        fun decode(data: ByteArray): SosPacket? {
            var offset = 0
            var sosId: String? = null
            var category: SosCategory? = null
            var createdAt: Long? = null
            var latitude: Double? = null
            var longitude: Double? = null

            while (offset + 2 <= data.size) {
                val type = data[offset]; offset += 1
                val length = data[offset].toInt() and 0xFF; offset += 1
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    Tlv.SOS_ID.id -> sosId = String(value, Charsets.UTF_8)
                    Tlv.CATEGORY.id -> { if (length == 1) category = SosCategory.fromWire(value[0]) }
                    Tlv.CREATED_AT.id -> { if (length == 8) createdAt = readLong(value) }
                    Tlv.LATITUDE.id -> { if (length == 8) latitude = Double.fromBits(readLong(value)) }
                    Tlv.LONGITUDE.id -> { if (length == 8) longitude = Double.fromBits(readLong(value)) }
                    else -> return null
                }
            }
            if (sosId == null || category == null || createdAt == null) return null
            return SosPacket(sosId, category, createdAt, latitude, longitude)
        }
    }
}
