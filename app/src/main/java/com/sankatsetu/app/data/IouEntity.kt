package com.sankatsetu.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mesh-signed promise-to-pay — see docs/PRD.md §F4 and
 * docs/adr/0012-mesh-iou-voucher.md. This is a local record of a promise,
 * never a real money movement; [status] tracks it through its lifecycle.
 */
@Entity(tableName = "ious", indices = [Index("createdAt"), Index("status")])
data class IouEntity(
    @PrimaryKey val iouId: String,
    /** True if we are owed this amount (we received the voucher); false if we owe it (we sent it). */
    val isOwedToMe: Boolean,
    val counterpartyPeerIdBase64: String,
    val counterpartyNickname: String,
    val amountPaise: Long,
    val memo: String,
    val createdAt: Long,
    val status: String, // pending | settled | rejected
    val signatureBase64: String
)
