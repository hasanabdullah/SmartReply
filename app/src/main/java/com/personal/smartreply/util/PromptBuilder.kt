package com.personal.smartreply.util

import com.personal.smartreply.data.local.EditHistoryEntity
import com.personal.smartreply.data.sms.SmsMessage
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptBuilder @Inject constructor() {

    fun buildSystemPrompt(toneDescription: String, isGroupChat: Boolean = false, personalFacts: String = ""): String = buildString {
        appendLine("You are a texting assistant. Your job is to suggest replies that match the user's personal texting style.")
        appendLine()
        appendLine("Style guidelines:")
        appendLine("- Match the user's typical message length, punctuation, capitalization, and formality level")
        appendLine("- Mirror their use of abbreviations, slang, and emoji patterns")
        appendLine("- Keep suggestions natural and conversational — not robotic or overly formal")
        appendLine("- Each suggestion should feel like something the user would actually send")
        if (personalFacts.isNotBlank()) {
            appendLine()
            appendLine("ABOUT THE USER (this is who is sending the messages labeled 'Me'):")
            appendLine(personalFacts)
            appendLine()
            appendLine("IMPORTANT: These facts are about the USER, not about the person they are texting.")
            appendLine("Do NOT ask the contact questions about the user's own life details.")
            appendLine("For example, if the user is a software engineer, do NOT suggest asking the contact 'how's the software engineering going?'")
        }
        if (isGroupChat) {
            appendLine()
            appendLine("This is a GROUP conversation with multiple participants.")
            appendLine("- Consider the group dynamic and who said what")
            appendLine("- The reply should fit naturally in the group context")
            appendLine("- You may address specific people by name if relevant")
        }
        if (toneDescription.isNotBlank()) {
            appendLine()
            appendLine("The user describes their texting style as: $toneDescription")
        }
        appendLine()
        appendLine("Output format: Provide exactly 3 numbered suggestions, one per line. Each has a specific purpose:")
        appendLine("1. [Direct reply — respond to the last 1-5 messages in the conversation]")
        appendLine("2. [Follow-up — reference something from 2 weeks to 1 month ago in the conversation that could use a follow-up or check-in]")
        appendLine("3. [Personal question — ask a genuine question about their life, family, work, health, hobbies, etc.]")
        appendLine()
        appendLine("IMPORTANT:")
        appendLine("- Suggestion 1 MUST directly address the most recent messages")
        appendLine("- Suggestion 2 MUST reference a specific older topic from the conversation history (2 weeks to 1 month back). Look for plans they mentioned, events, problems, goals, trips, etc. If no older context exists, reference the oldest available topic.")
        appendLine("- Suggestion 3 MUST be a thoughtful personal question that shows genuine interest")
        appendLine("- Do not include any other text, explanations, labels, or preamble. Just the 3 numbered suggestions.")
        appendLine()
        appendLine("WRITING RULES (strict):")
        appendLine("- NEVER use em dashes (—), en dashes (–), or double hyphens (--)")
        appendLine("- NEVER use asterisks (*) for emphasis")
        appendLine("- NEVER use semicolons (;)")
        appendLine("- NEVER use ellipsis (...) unless the user's style clearly includes them")
        appendLine("- Avoid overusing exclamation marks — one per message max, and only if the user's style uses them")
        appendLine("- Do NOT start messages with 'Hey!' or overly enthusiastic greetings")
        appendLine("- Do NOT use filler phrases like 'Just wanted to', 'I hope', 'I'd love to', 'Hope all is well'")
        appendLine("- Write like a real person texting, not like an AI assistant")
    }

    fun buildReplyPrompt(
        messages: List<SmsMessage>,
        contactName: String?,
        editHistory: List<EditHistoryEntity>,
        participants: Map<String, String?> = emptyMap()
    ): String = buildString {
        val isGroup = participants.size > 1
        val displayName = if (isGroup) {
            val names = participants.values.filterNotNull().ifEmpty { participants.keys.toList() }
            names.joinToString(", ")
        } else {
            contactName ?: "this contact"
        }

        if (editHistory.isNotEmpty()) {
            appendLine("Here are examples of how the user adjusted previous suggestions (learn from these):")
            for (edit in editHistory) {
                appendLine("You suggested: \"${edit.original}\"")
                appendLine("I changed it to: \"${edit.edited}\"")
                appendLine()
            }
            appendLine("---")
            appendLine()
        }

        // Analyze conversation context
        val gapContext = analyzeConversationGaps(messages)
        val topicSummary = extractRecentTopics(messages)

        if (isGroup) {
            appendLine("Group conversation with: $displayName")
            appendLine("Participants: ${participants.entries.joinToString(", ") { (addr, name) -> name ?: addr }}")
        } else {
            appendLine("Conversation with $displayName:")
        }

        if (gapContext != null) {
            appendLine()
            appendLine("IMPORTANT CONTEXT: $gapContext")
            appendLine()
        }

        if (topicSummary != null) {
            appendLine("Recent topics/themes in this conversation: $topicSummary")
            appendLine()
        }

        // Always include enough history for the follow-up suggestion (needs 2 weeks - 1 month context)
        val historySize = messages.size.coerceAtMost(150)
        val recentMessages = messages.takeLast(historySize)

        // Add timestamps to messages when there are notable gaps
        var lastDate = 0L
        for (msg in recentMessages) {
            val daysBetween = if (lastDate > 0) {
                TimeUnit.MILLISECONDS.toDays(msg.date - lastDate)
            } else 0

            if (daysBetween >= 1) {
                appendLine("--- ${formatGap(daysBetween)} later ---")
            }

            val sender = if (msg.isFromMe) {
                "Me"
            } else if (isGroup) {
                // Use resolved name or address for group chats
                msg.senderName ?: participants[msg.address] ?: msg.address
            } else {
                "Them"
            }
            appendLine("$sender: ${msg.body}")
            lastDate = msg.date
        }

        appendLine()
        appendLine("Suggest 3 replies I could send next:")
        appendLine("1. A direct reply to the most recent messages above")
        appendLine("2. A follow-up referencing something specific from earlier in this conversation (ideally 2 weeks to 1 month old)")
        appendLine("3. A genuine personal question about their life, family, work, health, hobbies, etc.")
    }

    fun buildSingleReplyPrompt(
        messages: List<SmsMessage>,
        contactName: String?,
        editHistory: List<EditHistoryEntity>,
        participants: Map<String, String?> = emptyMap(),
        category: String
    ): String = buildString {
        val isGroup = participants.size > 1
        val displayName = if (isGroup) {
            val names = participants.values.filterNotNull().ifEmpty { participants.keys.toList() }
            names.joinToString(", ")
        } else {
            contactName ?: "this contact"
        }

        if (editHistory.isNotEmpty()) {
            appendLine("Here are examples of how the user adjusted previous suggestions (learn from these):")
            for (edit in editHistory) {
                appendLine("You suggested: \"${edit.original}\"")
                appendLine("I changed it to: \"${edit.edited}\"")
                appendLine()
            }
            appendLine("---")
            appendLine()
        }

        if (isGroup) {
            appendLine("Group conversation with: $displayName")
        } else {
            appendLine("Conversation with $displayName:")
        }

        val historySize = messages.size.coerceAtMost(150)
        val recentMessages = messages.takeLast(historySize)

        var lastDate = 0L
        for (msg in recentMessages) {
            val daysBetween = if (lastDate > 0) {
                TimeUnit.MILLISECONDS.toDays(msg.date - lastDate)
            } else 0

            if (daysBetween >= 1) {
                appendLine("--- ${formatGap(daysBetween)} later ---")
            }

            val sender = if (msg.isFromMe) {
                "Me"
            } else if (isGroup) {
                msg.senderName ?: participants[msg.address] ?: msg.address
            } else {
                "Them"
            }
            appendLine("$sender: ${msg.body}")
            lastDate = msg.date
        }

        appendLine()
        appendLine("Suggest exactly 1 reply that is: $category")
        appendLine("Give a different suggestion than what you might have given before. Be creative.")
        appendLine("Output ONLY the suggestion text, nothing else. No numbering, no quotes, no explanation.")
    }

    private fun analyzeConversationGaps(messages: List<SmsMessage>): String? {
        if (messages.isEmpty()) return null

        val now = System.currentTimeMillis()
        val lastMessage = messages.last()
        val lastMyMessage = messages.lastOrNull { it.isFromMe }
        val lastTheirMessage = messages.lastOrNull { !it.isFromMe }

        val daysSinceLastMessage = TimeUnit.MILLISECONDS.toDays(now - lastMessage.date)
        val daysSinceMyReply = if (lastMyMessage != null) {
            TimeUnit.MILLISECONDS.toDays(now - lastMyMessage.date)
        } else null

        return buildString {
            // Case 1: They messaged and I haven't replied in a long time
            if (lastTheirMessage != null && lastMyMessage != null &&
                lastTheirMessage.date > lastMyMessage.date
            ) {
                val daysUnanswered = TimeUnit.MILLISECONDS.toDays(now - lastTheirMessage.date)
                if (daysUnanswered >= 30) {
                    append("Their last message was ${formatGap(daysUnanswered)} ago and I never replied. ")
                    append("Suggestions should acknowledge the late reply naturally (e.g., 'hey sorry for the late reply'). ")
                    append("Reference what they last said to show I actually read it.")
                } else if (daysUnanswered >= 7) {
                    append("Their last message was ${formatGap(daysUnanswered)} ago and I haven't replied yet. ")
                    append("Suggestions should feel natural for a delayed response — maybe briefly acknowledge the gap.")
                } else if (daysUnanswered >= 1) {
                    append("Their last message was ${formatGap(daysUnanswered)} ago. A slight delay, keep it casual.")
                } else return null
            }
            // Case 2: I was the last to message and the conversation went quiet
            else if (lastMyMessage != null && daysSinceMyReply != null && daysSinceMyReply >= 14) {
                append("I was the last to message ${formatGap(daysSinceMyReply)} ago and they didn't reply. ")
                append("This is a re-initiation after silence. Don't reference the old unanswered message directly — ")
                append("suggest something fresh that gives them an easy reason to respond.")
            }
            // Case 3: General long gap, both sides quiet
            else if (daysSinceLastMessage >= 30) {
                append("The conversation has been inactive for ${formatGap(daysSinceLastMessage)}. ")
                append("Suggestions should work as natural conversation re-starters that reference shared context from the message history.")
            } else return null
        }.ifEmpty { null }
    }

    private fun extractRecentTopics(messages: List<SmsMessage>): String? {
        if (messages.size < 3) return null

        // Look at the last cluster of messages (messages within 48h of each other)
        val recentCluster = mutableListOf<SmsMessage>()
        for (msg in messages.reversed()) {
            if (recentCluster.isEmpty()) {
                recentCluster.add(msg)
            } else {
                val gap = recentCluster.last().date - msg.date
                if (TimeUnit.MILLISECONDS.toHours(gap) <= 48) {
                    recentCluster.add(msg)
                } else break
            }
        }

        if (recentCluster.size < 2) return null

        val lastTheirMsg = recentCluster.firstOrNull { !it.isFromMe }?.body ?: return null
        return when {
            lastTheirMsg.contains("?") -> "Their last message was a question — make sure suggestions actually answer it"
            lastTheirMsg.any { it.isDigit() } && (lastTheirMsg.contains(":") || lastTheirMsg.contains("pm") || lastTheirMsg.contains("am")) ->
                "Their last message may reference a time/date — suggestions should address any scheduling"
            else -> null
        }
    }

    fun buildOpeningPrompt(
        contactName: String,
        context: String?,
        editHistory: List<EditHistoryEntity>
    ): String = buildString {
        if (editHistory.isNotEmpty()) {
            appendLine("Here are examples of how the user adjusted previous suggestions (learn from these):")
            for (edit in editHistory) {
                appendLine("You suggested: \"${edit.original}\"")
                appendLine("I changed it to: \"${edit.edited}\"")
                appendLine()
            }
            appendLine("---")
            appendLine()
        }

        appendLine("I want to start a conversation with $contactName.")
        if (!context.isNullOrBlank()) {
            appendLine("Context about this person: $context")
        }
        appendLine()
        appendLine("Suggest 3 opening messages I could send.")
    }

    fun parseSuggestions(response: String): List<String> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                line.removePrefix("1.").removePrefix("2.").removePrefix("3.")
                    .trim()
                    .removeSurrounding("\"")
            }
            .filter { it.isNotBlank() }
            .take(3)
            .map { sanitize(it) }
    }

    fun sanitize(text: String): String {
        return text
            .replace("—", ", ")   // em dash
            .replace("–", ", ")   // en dash
            .replace("--", ", ")  // double hyphen
            .replace(Regex("\\*+([^*]+)\\*+"), "$1")  // *bold* or **bold**
            .replace(";", ",")    // semicolons
            .replace(Regex("\\.{3,}"), "")  // ellipsis
            .replace(Regex(",\\s*,"), ",")  // double commas from replacements
            .replace(Regex("\\s{2,}"), " ") // collapse extra spaces
            .trim()
    }

    private fun formatGap(days: Long): String = when {
        days >= 365 -> "${days / 365} year${if (days / 365 > 1) "s" else ""}"
        days >= 30 -> "${days / 30} month${if (days / 30 > 1) "s" else ""}"
        days >= 7 -> "${days / 7} week${if (days / 7 > 1) "s" else ""}"
        days == 1L -> "1 day"
        else -> "$days days"
    }
}
