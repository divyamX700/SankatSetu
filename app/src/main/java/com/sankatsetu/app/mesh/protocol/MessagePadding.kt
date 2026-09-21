package com.sankatsetu.app.mesh.protocol

/**
 * PKCS#7-style padding to fixed size buckets, applied only to
 * `noiseEncrypted` / `noiseHandshake` frames so an observer can't correlate
 * ciphertext length with message content. Every other packet type — public
 * messages, announces, SOS/IOU broadcasts — goes out at its natural length;
 * see docs/concepts/ble-mesh-protocol.md#privacy-tradeoffs for why we
 * inherited this tradeoff from Bitchat rather than padding everything.
 *
 * Ported from Bitchat's `MessagePadding.swift` (public domain).
 */
object MessagePadding {
    private val BUCKETS = intArrayOf(256, 512, 1024, 2048)

    /**
     * Smallest bucket that fits [dataSize] plus at least one pad byte. If the
     * data is already too big for the largest bucket, or the padding needed
     * to reach a bucket would itself exceed 255 (PKCS#7's one-byte length
     * field), the frame is returned unpadded — this is Bitchat's own
     * documented gap (whitepaper §4.1), not something we introduced.
     */
    fun optimalBlockSize(dataSize: Int): Int {
        for (bucket in BUCKETS) {
            val padNeeded = bucket - dataSize
            if (padNeeded in 1..255) return bucket
        }
        return dataSize // unpadded fallback
    }

    fun pad(data: ByteArray, toSize: Int): ByteArray {
        if (toSize <= data.size) return data
        val padLength = toSize - data.size
        if (padLength > 255) return data // can't represent in one length byte — emit unpadded
        val result = ByteArray(toSize)
        System.arraycopy(data, 0, result, 0, data.size)
        for (i in data.size until toSize) result[i] = padLength.toByte()
        return result
    }

    /** Strips PKCS#7 padding if the trailing byte pattern looks valid; else returns input unchanged. */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLength = data.last().toInt() and 0xFF
        if (padLength == 0 || padLength > data.size) return data
        val start = data.size - padLength
        for (i in start until data.size) {
            if ((data[i].toInt() and 0xFF) != padLength) return data // not actually padding
        }
        return data.copyOfRange(0, start)
    }
}
