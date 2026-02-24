package com.personal.smartreply.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "edit_history")
data class EditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactAddress: String,
    val original: String,
    val edited: String,
    val timestamp: Long = System.currentTimeMillis()
)
