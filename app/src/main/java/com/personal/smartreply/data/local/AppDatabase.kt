package com.personal.smartreply.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [EditHistoryEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun editHistoryDao(): EditHistoryDao
}
