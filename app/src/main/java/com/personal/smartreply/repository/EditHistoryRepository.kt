package com.personal.smartreply.repository

import com.personal.smartreply.data.local.EditHistoryDao
import com.personal.smartreply.data.local.EditHistoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EditHistoryRepository @Inject constructor(
    private val dao: EditHistoryDao
) {
    private val maxRecords = 200

    suspend fun saveEdit(contactAddress: String, original: String, edited: String) {
        if (original.trim() == edited.trim()) return

        dao.insert(
            EditHistoryEntity(
                contactAddress = contactAddress,
                original = original,
                edited = edited
            )
        )
        pruneIfNeeded()
    }

    suspend fun getEditsForPrompt(contactAddress: String): List<EditHistoryEntity> {
        val contactEdits = dao.getForContact(contactAddress, 5)
        val globalEdits = dao.getGlobal(5)
        val seen = contactEdits.map { it.id }.toSet()
        val additional = globalEdits.filter { it.id !in seen }
        return (contactEdits + additional).take(10)
    }

    suspend fun getGlobalEdits(): List<EditHistoryEntity> {
        return dao.getGlobal(10)
    }

    private suspend fun pruneIfNeeded() {
        val count = dao.count()
        if (count > maxRecords) {
            dao.deleteOldest(count - maxRecords)
        }
    }
}
