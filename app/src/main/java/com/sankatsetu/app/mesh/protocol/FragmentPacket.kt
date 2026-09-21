package com.sankatsetu.app.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Payload carried inside a [MessageType.FRAGMENT] packet: one chunk of a
 * larger, fully-encoded [MeshPacket] that didn't fit in one BLE write.
 * Ported from Bitchat's fragment header layout (public domain) —
 * see docs/concepts/ble-mesh-protocol.md#fragmentation.
 *
 * Wire format (all big-endian):
 * ```
 * +------------+-------+-------+----------+
 * | fragmentID | index | total |  chunk   |
 * |  8 bytes   |2 bytes|2 bytes| variable |
 * +------------+-------+-------+----------+
 * ```
 * The reassembled byte blob (all chunks concatenated in index order) is
 * exactly the original packet's [BinaryProtocol]-encoded bytes — fed back
 * into [BinaryProtocol.decode] once complete, not a separate format.
 */
data class FragmentPacket(
    val fragmentId: Long,
    val index: Int,
    val total: Int,
    val chunk: ByteArray
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(12 + chunk.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(fragmentId)
        buf.putShort(index.toShort())
        buf.putShort(total.toShort())
        buf.put(chunk)
        return buf.array()
    }

    companion object {
        const val HEADER_SIZE = 12

        fun decode(data: ByteArray): FragmentPacket? {
            if (data.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val fragmentId = buf.long
            val index = buf.short.toInt() and 0xFFFF
            val total = buf.short.toInt() and 0xFFFF
            if (total <= 0 || index < 0 || index >= total) return null
            val chunk = ByteArray(buf.remaining()).also { buf.get(it) }
            return FragmentPacket(fragmentId, index, total, chunk)
        }

        /**
         * Splits [fullBytes] (an already fully-encoded [MeshPacket]) into
         * fragments of at most [chunkSize] bytes each, sharing one random
         * fragment ID. Returns single-element list unchanged (as raw bytes,
         * not wrapped) when [fullBytes] already fits — callers should check
         * `fullBytes.size <= chunkSize` first and skip fragmentation entirely
         * in that case; this function always fragments when called.
         */
        fun split(fullBytes: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<FragmentPacket> {
            require(chunkSize > 0)
            val fragmentId = SecureRandom().nextLong()
            val total = (fullBytes.size + chunkSize - 1) / chunkSize
            return (0 until total).map { i ->
                val start = i * chunkSize
                val end = minOf(start + chunkSize, fullBytes.size)
                FragmentPacket(fragmentId, i, total, fullBytes.copyOfRange(start, end))
            }
        }

        // ~469 bytes matches Bitchat's whitepaper (BLE MTU minus header/ATT overhead).
        const val DEFAULT_CHUNK_SIZE = 469
    }
}
