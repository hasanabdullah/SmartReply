package com.personal.smartreply.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A message scraped from the Google Messages UI via accessibility */
data class ScrapedMessage(
    val sender: String,   // sender name, or "Me" for outgoing
    val body: String,
    val isFromMe: Boolean,
    val timeLabel: String  // e.g., "Wednesday 2:36 PM"
)

data class ActiveConversation(
    val threadId: String,
    val contactName: String?,
    val scrapedMessages: List<ScrapedMessage> = emptyList()
)

@Singleton
class ActiveConversationManager @Inject constructor() {
    private val _activeConversation = MutableStateFlow<ActiveConversation?>(null)
    val activeConversation: StateFlow<ActiveConversation?> = _activeConversation.asStateFlow()

    private val _isMessagesAppOpen = MutableStateFlow(false)
    val isMessagesAppOpen: StateFlow<Boolean> = _isMessagesAppOpen.asStateFlow()

    private val _pendingMessage = Channel<String>(Channel.BUFFERED)
    val pendingMessage = _pendingMessage.receiveAsFlow()

    fun setActiveConversation(conversation: ActiveConversation?) {
        _activeConversation.value = conversation
    }

    fun setMessagesAppOpen(isOpen: Boolean) {
        _isMessagesAppOpen.value = isOpen
        if (!isOpen) {
            _activeConversation.value = null
        }
    }

    fun sendMessage(text: String) {
        _pendingMessage.trySend(text)
    }

    fun clear() {
        _activeConversation.value = null
        _isMessagesAppOpen.value = false
    }
}
