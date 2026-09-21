package com.sankatsetu.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // dedup: SeenMessageCache already filtered relays, but belt-and-suspenders on the DB
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE threadPeerIdBase64 = :peerIdBase64 ORDER BY sentAt ASC")
    fun observeThread(peerIdBase64: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadPeerIdBase64 IS NULL ORDER BY sentAt ASC")
    fun observePublicChannel(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    /**
     * Only advances status forward (queued < sending < sent < delivered < read):
     * a late-arriving "delivered" ack must never downgrade a message that a
     * subsequent "read" receipt already advanced past it, and vice versa if
     * they arrive out of order.
     */
    @Query(
        """UPDATE messages SET status = :status WHERE messageId = :messageId AND
           (CASE status WHEN 'queued' THEN 0 WHEN 'sending' THEN 1 WHEN 'sent' THEN 2 WHEN 'delivered' THEN 3 WHEN 'read' THEN 4 ELSE -1 END) <
           (CASE :status WHEN 'queued' THEN 0 WHEN 'sending' THEN 1 WHEN 'sent' THEN 2 WHEN 'delivered' THEN 3 WHEN 'read' THEN 4 ELSE -1 END)"""
    )
    suspend fun advanceStatus(messageId: String, status: String)

    @Query("SELECT * FROM messages WHERE threadPeerIdBase64 = :peerIdBase64 AND isOutgoing = 0 AND readReceiptSent = 0")
    suspend fun getUnacknowledgedIncoming(peerIdBase64: String): List<MessageEntity>

    @Query("UPDATE messages SET readReceiptSent = 1 WHERE messageId = :messageId")
    suspend fun markReadReceiptSent(messageId: String)

    /** Clears one thread's local history only — the peer's own copy, and the mesh itself, are untouched. */
    @Query("DELETE FROM messages WHERE threadPeerIdBase64 = :peerIdBase64")
    suspend fun deleteThread(peerIdBase64: String)
}
