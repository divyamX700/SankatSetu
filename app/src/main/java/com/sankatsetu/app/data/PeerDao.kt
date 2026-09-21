package com.sankatsetu.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerIdBase64 = :peerIdBase64 LIMIT 1")
    suspend fun getByPeerId(peerIdBase64: String): PeerEntity?

    /**
     * Updates on every announce from an already-known peer, nickname
     * included — a peer renaming themselves on their own phone re-announces
     * immediately (see ChatViewModel.renameSelf), and the old version of
     * this query only touched lastSeen/hopCount, never nickname. That meant
     * a rename only ever showed up live via ChatViewModel's in-memory
     * knownNicknames map, never actually saved — a cold app restart before
     * the renamed peer's next announce would show their stale, pre-rename
     * name again. Now the database is the same source of truth the live
     * map already was.
     */
    @Query("UPDATE peers SET lastSeen = :timestamp, lastKnownHopCount = :hopCount, nickname = :nickname, latitude = :latitude, longitude = :longitude WHERE peerIdBase64 = :peerIdBase64")
    suspend fun touch(peerIdBase64: String, timestamp: Long, hopCount: Int, nickname: String, latitude: Double?, longitude: Double?)

    /**
     * Forgets a peer record — the mesh has no concept of "delete this
     * device," so this only removes local history. If the same phone
     * announces again it reappears as a fresh row; this doesn't block them.
     * Exists because repeated reinstalls during development left multiple
     * dead identities for the same physical phones piling up in the peer
     * list with no way to clear them — a real user hitting stale entries
     * from an old device needs the same escape hatch.
     */
    @Query("DELETE FROM peers WHERE peerIdBase64 = :peerIdBase64")
    suspend fun delete(peerIdBase64: String)
}
