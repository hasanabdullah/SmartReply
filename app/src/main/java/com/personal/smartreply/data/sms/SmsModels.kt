package com.personal.smartreply.data.sms

data class SmsMessage(
    val id: Long,
    val threadId: String,
    val address: String,
    val body: String,
    val date: Long,
    val isFromMe: Boolean,
    val senderName: String? = null
)

data class SmsThread(
    val threadId: String,
    val address: String,
    val contactName: String?,
    val lastMessage: String,
    val lastDate: Long,
    val messageCount: Int,
    val addresses: List<String> = listOf(),
    val contactNames: List<String> = listOf()
) {
    val isGroupChat: Boolean get() = addresses.size > 1
    val displayName: String get() = when {
        isGroupChat -> contactNames.filter { it.isNotBlank() }.joinToString(", ").ifEmpty {
            addresses.joinToString(", ")
        }
        else -> contactName ?: address
    }
}
