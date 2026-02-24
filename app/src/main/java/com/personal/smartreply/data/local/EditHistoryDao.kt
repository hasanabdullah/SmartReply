package com.personal.smartreply.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EditHistoryDao {
    @Insert
    suspend fun insert(entry: EditHistoryEntity)

    @Query("SELECT * FROM edit_history WHERE contactAddress = :address ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getForContact(address: String, limit: Int = 5): List<EditHistoryEntity>

    @Query("SELECT * FROM edit_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getGlobal(limit: Int = 5): List<EditHistoryEntity>

    @Query("SELECT COUNT(*) FROM edit_history")
    suspend fun count(): Int

    @Query("DELETE FROM edit_history WHERE id IN (SELECT id FROM edit_history ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}
