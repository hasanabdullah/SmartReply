package com.personal.smartreply.repository

import com.personal.smartreply.data.contacts.ContactsReader
import com.personal.smartreply.data.local.EditHistoryEntity
import com.personal.smartreply.data.local.SettingsDataStore
import com.personal.smartreply.data.remote.ClaudeApi
import com.personal.smartreply.data.remote.ClaudeMessage
import com.personal.smartreply.data.remote.ClaudeRequest
import com.personal.smartreply.data.remote.ClaudeStreamParser
import com.personal.smartreply.data.sms.SmsMessage
import com.personal.smartreply.data.sms.SmsReader
import com.personal.smartreply.data.sms.SmsThread
import com.personal.smartreply.util.PromptBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsRepository @Inject constructor(
    private val smsReader: SmsReader,
    private val contactsReader: ContactsReader,
    private val claudeApi: ClaudeApi,
    private val streamParser: ClaudeStreamParser,
    private val settingsDataStore: SettingsDataStore,
    private val editHistoryRepository: EditHistoryRepository,
    private val promptBuilder: PromptBuilder
) {
    fun getThreads(): List<SmsThread> {
        val threads = smsReader.getThreads()
        return threads.map { thread ->
            val nameMap = contactsReader.getContactNames(thread.addresses)
            val resolvedNames = thread.addresses.map { addr -> nameMap[addr] ?: addr }
            thread.copy(
                contactName = nameMap[thread.address],
                contactNames = resolvedNames
            )
        }
    }

    fun getMessages(threadId: String): List<SmsMessage> {
        return smsReader.getMessagesForThread(threadId)
    }

    fun getMessagesWithNames(threadId: String, participants: Map<String, String?>): List<SmsMessage> {
        val messages = smsReader.getMessagesForThread(threadId)
        return messages.map { msg ->
            if (msg.isFromMe) msg
            else msg.copy(senderName = participants[msg.address])
        }
    }

    fun getContactName(address: String): String? {
        return contactsReader.getContactName(address)
    }

    fun getThreadByContactName(name: String): SmsThread? {
        val threads = getThreads()
        // Exact contact name match first
        val exactMatch = threads.firstOrNull { thread ->
            thread.contactName.equals(name, ignoreCase = true) ||
            thread.contactNames.any { it.equals(name, ignoreCase = true) }
        }
        if (exactMatch != null) return exactMatch

        // Partial name match (e.g., "John" matches "John Smith")
        val partialMatch = threads.firstOrNull { thread ->
            thread.contactName?.contains(name, ignoreCase = true) == true ||
            thread.contactNames.any { it.contains(name, ignoreCase = true) }
        }
        if (partialMatch != null) return partialMatch

        // Phone number match
        val normalized = name.filter { it.isDigit() }
        if (normalized.length >= 7) {
            return threads.firstOrNull { thread ->
                thread.addresses.any { addr ->
                    addr.filter { it.isDigit() }.endsWith(normalized) ||
                    normalized.endsWith(addr.filter { it.isDigit() })
                }
            }
        }

        return null
    }

    suspend fun suggestReplies(
        messages: List<SmsMessage>,
        contactAddress: String,
        contactName: String?,
        participants: Map<String, String?> = emptyMap()
    ): Result<List<String>> = runCatching {
        val apiKey = settingsDataStore.apiKey.first()
        val model = settingsDataStore.model.first()
        val tone = settingsDataStore.toneDescription.first()
        val personalFacts = settingsDataStore.personalFacts.first()

        if (apiKey.isBlank()) error("API key not set. Go to Settings to add your Claude API key.")

        val editHistory = editHistoryRepository.getEditsForPrompt(contactAddress)
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts)
        val userPrompt = promptBuilder.buildReplyPrompt(messages, contactName, editHistory, participants)

        val request = ClaudeRequest(
            model = model,
            maxTokens = 300,
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = userPrompt))
        )

        val response = claudeApi.createMessage(apiKey, request = request)
        val text = response.content.firstOrNull()?.text ?: ""
        promptBuilder.parseSuggestions(text)
    }

    suspend fun suggestSingle(
        messages: List<SmsMessage>,
        contactAddress: String,
        contactName: String?,
        participants: Map<String, String?> = emptyMap(),
        category: String
    ): Result<String> = runCatching {
        val apiKey = settingsDataStore.apiKey.first()
        val model = settingsDataStore.model.first()
        val tone = settingsDataStore.toneDescription.first()
        val personalFacts = settingsDataStore.personalFacts.first()

        if (apiKey.isBlank()) error("API key not set.")

        val editHistory = editHistoryRepository.getEditsForPrompt(contactAddress)
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts)
        val userPrompt = promptBuilder.buildSingleReplyPrompt(messages, contactName, editHistory, participants, category)

        val request = ClaudeRequest(
            model = model,
            maxTokens = 150,
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = userPrompt))
        )

        val response = claudeApi.createMessage(apiKey, request = request)
        val text = response.content.firstOrNull()?.text ?: ""
        val raw = text.trim().removePrefix("1.").removePrefix("2.").removePrefix("3.")
            .trim().removeSurrounding("\"")
        promptBuilder.sanitize(raw)
    }

    fun streamReplies(
        messages: List<SmsMessage>,
        contactAddress: String,
        contactName: String?,
        editHistory: List<EditHistoryEntity>,
        apiKey: String,
        model: String,
        tone: String,
        personalFacts: String = "",
        participants: Map<String, String?> = emptyMap()
    ): Flow<String> {
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts)
        val userPrompt = promptBuilder.buildReplyPrompt(messages, contactName, editHistory, participants)

        val request = ClaudeRequest(
            model = model,
            maxTokens = 300,
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = userPrompt))
        )

        return streamParser.stream(apiKey, request)
    }

    suspend fun suggestOpening(
        contactName: String,
        context: String?
    ): Result<List<String>> = runCatching {
        val apiKey = settingsDataStore.apiKey.first()
        val model = settingsDataStore.model.first()
        val tone = settingsDataStore.toneDescription.first()
        val personalFacts = settingsDataStore.personalFacts.first()

        if (apiKey.isBlank()) error("API key not set. Go to Settings to add your Claude API key.")

        val editHistory = editHistoryRepository.getGlobalEdits()
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, personalFacts = personalFacts)
        val userPrompt = promptBuilder.buildOpeningPrompt(contactName, context, editHistory)

        val request = ClaudeRequest(
            model = model,
            maxTokens = 300,
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = userPrompt))
        )

        val response = claudeApi.createMessage(apiKey, request = request)
        val text = response.content.firstOrNull()?.text ?: ""
        promptBuilder.parseSuggestions(text)
    }

    suspend fun testConnection(): Result<String> {
        return try {
            val apiKey = settingsDataStore.apiKey.first().trim()
            val model = settingsDataStore.model.first()

            if (apiKey.isBlank()) return Result.failure(Exception("API key not set."))

            val request = ClaudeRequest(
                model = model,
                maxTokens = 20,
                messages = listOf(ClaudeMessage(role = "user", content = "Say 'Connected!' and nothing else."))
            )

            val response = claudeApi.createMessage(apiKey, request = request)
            Result.success(response.content.firstOrNull()?.text ?: "No response")
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: "No details"
            Result.failure(Exception("HTTP ${e.code()}: $errorBody"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
