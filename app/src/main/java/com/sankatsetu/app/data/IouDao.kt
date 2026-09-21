package com.sankatsetu.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IouDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // dedup: a re-relayed IOU envelope must never double-count
    suspend fun insert(iou: IouEntity)

    @Query("SELECT * FROM ious ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<IouEntity>>

    @Query("SELECT * FROM ious WHERE iouId = :iouId LIMIT 1")
    suspend fun getById(iouId: String): IouEntity?

    @Query("UPDATE ious SET status = :status WHERE iouId = :iouId")
    suspend fun updateStatus(iouId: String, status: String)
}
