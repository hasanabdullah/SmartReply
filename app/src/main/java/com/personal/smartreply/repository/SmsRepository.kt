package com.personal.smartreply.repository

import android.util.Log
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
    // Cache threads for 30 seconds to avoid re-scanning 1000+ threads on every overlay action
    @Volatile private var cachedThreads: List<SmsThread>? = null
    @Volatile private var cacheTimestamp: Long = 0
    private val cacheTtlMs = 30_000L

    fun getThreads(): List<SmsThread> {
        val now = System.currentTimeMillis()
        cachedThreads?.let { cached ->
            if (now - cacheTimestamp < cacheTtlMs) return cached
        }

        val threads = smsReader.getThreads()
        val resolved = threads.map { thread ->
            val nameMap = contactsReader.getContactNames(thread.addresses)
            val resolvedNames = thread.addresses.map { addr -> nameMap[addr] ?: addr }
            thread.copy(
                contactName = nameMap[thread.address],
                contactNames = resolvedNames
            )
        }
        cachedThreads = resolved
        cacheTimestamp = now
        return resolved
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

    /**
     * Get merged messages from ALL threads that share the same contact address.
     * Android splits 1-on-1 conversations across multiple thread IDs (SMS vs MMS threads),
     * so we merge them to get complete conversation context.
     */
    fun getMergedMessagesForContact(primaryThreadId: String, contactAddress: String, participants: Map<String, String?>): List<SmsMessage> {
        val allThreads = getThreads()
        // Find all non-group threads with the same contact address
        val matchingThreadIds = allThreads
            .filter { !it.isGroupChat && it.addresses.any { addr ->
                val a = addr.filter { c -> c.isDigit() }
                val b = contactAddress.filter { c -> c.isDigit() }
                a == b || a.endsWith(b) || b.endsWith(a)
            }}
            .map { it.threadId }
            .toSet()

        Log.d(TAG, "getMergedMessages: primary=$primaryThreadId, contact=$contactAddress, merging ${matchingThreadIds.size} threads: $matchingThreadIds")

        // If only one thread or none found, fall back to single thread
        if (matchingThreadIds.size <= 1) {
            return getMessagesWithNames(primaryThreadId, participants)
        }

        // Merge messages from all matching threads
        val allMessages = matchingThreadIds.flatMap { tid ->
            smsReader.getMessagesForThread(tid)
        }

        // Deduplicate by (date within 2s, same body start)
        val deduplicated = mutableListOf<SmsMessage>()
        for (msg in allMessages.sortedByDescending { it.date }) {
            val isDup = deduplicated.any { existing ->
                Math.abs(existing.date - msg.date) < 2000 &&
                existing.body.take(20) == msg.body.take(20)
            }
            if (!isDup) deduplicated.add(msg)
        }

        val sorted = deduplicated
            .sortedByDescending { it.date }
            .take(100)
            .sortedBy { it.date }

        Log.d(TAG, "getMergedMessages: ${allMessages.size} total -> ${sorted.size} after dedup+limit")

        return sorted.map { msg ->
            if (msg.isFromMe) msg
            else msg.copy(senderName = participants[msg.address])
        }
    }

    fun getContactName(address: String): String? {
        return contactsReader.getContactName(address)
    }

    fun getThreadByContactName(name: String): SmsThread? {
        val threads = getThreads()
        val isSingleName = !name.contains(",") && !name.contains("&")

        Log.d(TAG, "getThreadByContactName('$name') isSingleName=$isSingleName totalThreads=${threads.size}")

        // Debug: log all threads that match this name
        val matchingThreads = threads.filter { thread ->
            thread.contactName.equals(name, ignoreCase = true) ||
            thread.contactNames.any { it.equals(name, ignoreCase = true) } ||
            thread.contactName?.contains(name, ignoreCase = true) == true ||
            thread.contactNames.any { it.contains(name, ignoreCase = true) }
        }
        for (t in matchingThreads) {
            Log.d(TAG, "  CANDIDATE: id=${t.threadId} isGroup=${t.isGroupChat} " +
                "addrCount=${t.addresses.size} addresses=${t.addresses} " +
                "contactName=${t.contactName} contactNames=${t.contactNames} msgCount=${t.messageCount}")
        }

        // For single names: prefer 1-on-1 threads over group threads
        // When multiple threads match, pick the one with the most messages (best context)
        if (isSingleName) {
            // Exact match on 1-on-1 threads — pick thread with most messages
            val exactSingles = threads.filter { thread ->
                !thread.isGroupChat && (
                    thread.contactName.equals(name, ignoreCase = true) ||
                    thread.contactNames.any { it.equals(name, ignoreCase = true) }
                )
            }
            if (exactSingles.isNotEmpty()) {
                val best = exactSingles.maxByOrNull { it.messageCount } ?: exactSingles.first()
                Log.d(TAG, "  RESULT(exactSingle): id=${best.threadId} isGroup=${best.isGroupChat} msgCount=${best.messageCount} (of ${exactSingles.size} candidates)")
                return best
            }

            // Partial match on 1-on-1 threads — pick thread with most messages
            val partialSingles = threads.filter { thread ->
                !thread.isGroupChat && (
                    thread.contactName?.contains(name, ignoreCase = true) == true ||
                    thread.contactNames.any { it.contains(name, ignoreCase = true) }
                )
            }
            if (partialSingles.isNotEmpty()) {
                val best = partialSingles.maxByOrNull { it.messageCount } ?: partialSingles.first()
                Log.d(TAG, "  RESULT(partialSingle): id=${best.threadId} isGroup=${best.isGroupChat} msgCount=${best.messageCount} (of ${partialSingles.size} candidates)")
                return best
            }

            // Fall back to any thread (including groups) with exact match — most messages
            val exactAny = threads.filter { thread ->
                thread.contactName.equals(name, ignoreCase = true) ||
                thread.contactNames.any { it.equals(name, ignoreCase = true) }
            }
            if (exactAny.isNotEmpty()) {
                val best = exactAny.maxByOrNull { it.messageCount } ?: exactAny.first()
                Log.d(TAG, "  RESULT(exactAny): id=${best.threadId} isGroup=${best.isGroupChat} msgCount=${best.messageCount} (of ${exactAny.size} candidates)")
                return best
            }
        }

        // Group chat match: title is comma-separated names like "John, Jane, Bob"
        // or "John, Jane & 2 others". Match against thread.displayName or individual names.
        if (!isSingleName) {
            // Try exact displayName match first
            val displayMatch = threads.firstOrNull { thread ->
                thread.isGroupChat && thread.displayName.equals(name, ignoreCase = true)
            }
            if (displayMatch != null) return displayMatch

            // Split title into individual names and find thread containing all of them
            val titleNames = name
                .replace("&", ",")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.matches(Regex("\\d+ others?")) }

            if (titleNames.isNotEmpty()) {
                val groupMatch = threads
                    .filter { it.isGroupChat }
                    .firstOrNull { thread ->
                        titleNames.all { titleName ->
                            thread.contactNames.any { contactName ->
                                contactName.equals(titleName, ignoreCase = true) ||
                                contactName.contains(titleName, ignoreCase = true) ||
                                titleName.contains(contactName, ignoreCase = true)
                            }
                        }
                    }
                if (groupMatch != null) return groupMatch
            }
        }

        // Partial name match (any thread)
        val partialMatch = threads.firstOrNull { thread ->
            thread.contactName?.contains(name, ignoreCase = true) == true ||
            thread.contactNames.any { it.contains(name, ignoreCase = true) }
        }
        if (partialMatch != null) {
            Log.d(TAG, "  RESULT(partial): id=${partialMatch.threadId} isGroup=${partialMatch.isGroupChat}")
            return partialMatch
        }

        // Phone number match
        val normalized = name.filter { it.isDigit() }
        if (normalized.length >= 7) {
            val phoneMatch = threads.firstOrNull { thread ->
                thread.addresses.any { addr ->
                    addr.filter { it.isDigit() }.endsWith(normalized) ||
                    normalized.endsWith(addr.filter { it.isDigit() })
                }
            }
            if (phoneMatch != null) {
                Log.d(TAG, "  RESULT(phone): id=${phoneMatch.threadId} isGroup=${phoneMatch.isGroupChat}")
            }
            return phoneMatch
        }

        Log.d(TAG, "  RESULT: no match found for '$name'")
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
        val persona = settingsDataStore.persona.first()

        if (apiKey.isBlank()) error("API key not set. Go to Settings to add your Claude API key.")

        val editHistory = editHistoryRepository.getEditsForPrompt(contactAddress)
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts, persona)
        val userPrompt = promptBuilder.buildReplyPrompt(messages, contactName, editHistory, participants, persona)

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
        val persona = settingsDataStore.persona.first()

        if (apiKey.isBlank()) error("API key not set.")

        val editHistory = editHistoryRepository.getEditsForPrompt(contactAddress)
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts, persona)
        val userPrompt = promptBuilder.buildSingleReplyPrompt(messages, contactName, editHistory, participants, category, persona)

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
        participants: Map<String, String?> = emptyMap(),
        persona: String = ""
    ): Flow<String> {
        val isGroup = participants.size > 1
        val systemPrompt = promptBuilder.buildSystemPrompt(tone, isGroup, personalFacts, persona)
        val userPrompt = promptBuilder.buildReplyPrompt(messages, contactName, editHistory, participants, persona)

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

    companion object {
        private const val TAG = "SmsRepository"
    }
}
