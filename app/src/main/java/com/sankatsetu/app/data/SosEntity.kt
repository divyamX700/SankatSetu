package com.sankatsetu.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A received or sent SOS broadcast — see docs/TODO.md's SOS ideation and
 * `mesh/emergency/SosManager.kt`. The "small reviewable log" the ideation
 * asked for, since more than one person could be sending SOS at once and a
 * single interrupting dialog can't be the only record of that.
 *
 * [isOutgoing] rows are this device's own sends, inserted locally the
 * moment they're broadcast (the mesh never echoes your own packet back to
 * you) and always [acknowledged] since there's nothing to acknowledge about
 * your own report.
 */
@Entity(tableName = "sos_alerts", indices = [Index("receivedAt")])
data class SosEntity(
    @PrimaryKey val sosId: String,
    val senderPeerIdBase64: String,
    val senderNickname: String,
    val category: String, // SosCategory.name
    val hopCount: Int,
    val receivedAt: Long,
    val acknowledged: Boolean,
    val isOutgoing: Boolean,
    // Nullable: a location fix isn't guaranteed within the few seconds
    // SosManager.broadcastSos waits before sending regardless -- see its
    // own doc for why this can't just block until GPS resolves.
    val latitude: Double? = null,
    val longitude: Double? = null
)
