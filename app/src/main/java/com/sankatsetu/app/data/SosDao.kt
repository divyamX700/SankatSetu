package com.sankatsetu.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SosDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // dedup: a re-relayed SOS must never double-count as a second alert
    suspend fun insert(alert: SosEntity)

    @Query("SELECT * FROM sos_alerts ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<SosEntity>>

    @Query("UPDATE sos_alerts SET acknowledged = 1 WHERE sosId = :sosId")
    suspend fun acknowledge(sosId: String)

    @Query("DELETE FROM sos_alerts")
    suspend fun deleteAll()
}
