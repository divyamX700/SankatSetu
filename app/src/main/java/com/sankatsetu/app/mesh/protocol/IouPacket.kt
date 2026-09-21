package com.sankatsetu.app.mesh.protocol

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * A mesh-signed promise-to-pay, carried as the payload of a
 * [MessageType.IOU_ENVELOPE] packet — see docs/PRD.md §F4 and
 * docs/adr/0012-mesh-iou-voucher.md.
 *
 * This is a **promise**, not a bearer instrument or real money movement: the
 * sender cryptographically signs "I owe you this amount," the mesh delivers
 * it (working with zero internet, exactly like chat), and actually paying it
 * back still happens through a real payment rail (UPI, cash, anything else)
 * that the sender settles once they have connectivity or are face to face —
 * this app records the promise and its settlement status, it never touches
 * a bank account or moves money itself.
 *
 * The signature is computed over [signingBytes] using the sender's
 * Keystore-held ECDSA key (see [com.sankatsetu.app.mesh.crypto.Identity])
 * and verified by the receiver against the sender's signing public key from
 * their [AnnouncementPacket] — see `IouManager.handleInbound` for why a
 * voucher with a signature that doesn't check out is dropped before it ever
 * reaches the UI as "money someone owes."
 */
data class IouPacket(
    val iouId: String = UUID.randomUUID().toString(),
    val amountPaise: Long,
    val memo: String,
    val createdAt: Long = System.currentTimeMillis(),
    val signature: ByteArray = ByteArray(0)
) {
    private enum class Tlv(val id: Byte) {
        IOU_ID(0x00), AMOUNT_PAISE(0x01), MEMO(0x02), CREATED_AT(0x03), SIGNATURE(0x04)
    }

    /** The bytes the sender signs and the receiver verifies against — everything except the signature itself. */
    fun signingBytes(): ByteArray {
        val idBytes = iouId.toByteArray(Charsets.UTF_8)
        val memoBytes = memo.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(idBytes.size); out.write(idBytes)
        writeLong(out, amountPaise)
        out.write(memoBytes.size.coerceAtMost(255)); out.write(memoBytes.take(255).toByteArray())
        writeLong(out, createdAt)
        return out.toByteArray()
    }

    fun encode(): ByteArray? {
        val idBytes = iouId.toByteArray(Charsets.UTF_8)
        val memoBytes = memo.toByteArray(Charsets.UTF_8)
        if (idBytes.size > 255 || memoBytes.size > 255 || signature.size > 255) return null

        val out = ByteArrayOutputStream()
        out.write(Tlv.IOU_ID.id.toInt()); out.write(idBytes.size); out.write(idBytes)
        out.write(Tlv.AMOUNT_PAISE.id.toInt()); out.write(8); writeLong(out, amountPaise)
        out.write(Tlv.MEMO.id.toInt()); out.write(memoBytes.size); out.write(memoBytes)
        out.write(Tlv.CREATED_AT.id.toInt()); out.write(8); writeLong(out, createdAt)
        out.write(Tlv.SIGNATURE.id.toInt()); out.write(signature.size); out.write(signature)
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

        fun decode(data: ByteArray): IouPacket? {
            var offset = 0
            var iouId: String? = null
            var amountPaise: Long? = null
            var memo: String? = null
            var createdAt: Long? = null
            var signature: ByteArray? = null

            while (offset + 2 <= data.size) {
                val type = data[offset]; offset += 1
                val length = data[offset].toInt() and 0xFF; offset += 1
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    Tlv.IOU_ID.id -> iouId = String(value, Charsets.UTF_8)
                    Tlv.AMOUNT_PAISE.id -> amountPaise = readLong(value)
                    Tlv.MEMO.id -> memo = String(value, Charsets.UTF_8)
                    Tlv.CREATED_AT.id -> createdAt = readLong(value)
                    Tlv.SIGNATURE.id -> signature = value
                    else -> return null
                }
            }
            if (iouId == null || amountPaise == null || memo == null || createdAt == null || signature == null) return null
            return IouPacket(iouId, amountPaise, memo, createdAt, signature)
        }
    }
}

/** Carried as the payload of a [MessageType.IOU_SETTLEMENT_ACK] packet. */
data class IouSettlementAck(val iouId: String, val settledAt: Long = System.currentTimeMillis()) {
    fun encode(): ByteArray {
        val idBytes = iouId.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(idBytes.size); out.write(idBytes)
        for (i in 7 downTo 0) out.write(((settledAt ushr (i * 8)) and 0xFF).toInt())
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): IouSettlementAck? {
            if (data.isEmpty()) return null
            val idLen = data[0].toInt() and 0xFF
            if (1 + idLen + 8 != data.size) return null
            val iouId = String(data.copyOfRange(1, 1 + idLen), Charsets.UTF_8)
            var settledAt = 0L
            for (i in 0 until 8) settledAt = (settledAt shl 8) or (data[1 + idLen + i].toLong() and 0xFF)
            return IouSettlementAck(iouId, settledAt)
        }
    }
}
