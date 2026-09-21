package com.sankatsetu.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat message, sent or received. Day 1 scope covers plain text over a
 * live Noise session; `channel` (for `#public`/`#sos`/`#official`) and the
 * courier/gossip-sync bookkeeping columns from docs/PRD.md §9.1 land Day 2-3.
 */
@Entity(
    tableName = "messages",
    indices = [Index("threadPeerIdBase64"), Index("sentAt")]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    /** Null for a public broadcast; the peer ID of the other party for a 1:1 thread. */
    val threadPeerIdBase64: String?,
    val senderPeerIdBase64: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val hopCount: Int,
    val status: String, // queued | sending | sent | delivered | read
    val isOutgoing: Boolean,
    /** Local-only bookkeeping: have *we* (the receiver of this message) already told the sender we've seen it? */
    val readReceiptSent: Boolean = false
)
