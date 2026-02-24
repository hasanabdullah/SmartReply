package com.personal.smartreply.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.smartreply.data.local.SettingsDataStore
import com.personal.smartreply.data.sms.SmsMessage
import com.personal.smartreply.repository.EditHistoryRepository
import com.personal.smartreply.repository.SmsRepository
import com.personal.smartreply.util.PromptBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<SmsMessage> = emptyList(),
    val contactName: String? = null,
    val contactAddress: String = "",
    val suggestions: List<String> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val smsRepository: SmsRepository,
    private val editHistoryRepository: EditHistoryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val promptBuilder: PromptBuilder
) : ViewModel() {

    private val threadId: String = savedStateHandle["threadId"] ?: ""

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadMessages()
    }

    private fun loadMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val messages = smsRepository.getMessages(threadId)
                val address = messages.firstOrNull { !it.isFromMe }?.address ?: ""
                val contactName = if (address.isNotBlank()) {
                    smsRepository.getContactName(address)
                } else null

                _uiState.value = _uiState.value.copy(
                    messages = messages,
                    contactName = contactName,
                    contactAddress = address
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to load messages"
                )
            }
        }
    }

    fun suggestReply() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState.messages.isEmpty()) return@launch

            _uiState.value = currentState.copy(
                isLoadingSuggestions = true,
                suggestions = emptyList(),
                error = null,
                streamingText = "",
                isStreaming = false
            )

            val apiKey = settingsDataStore.apiKey.first()
            val model = settingsDataStore.model.first()
            val tone = settingsDataStore.toneDescription.first()
            val personalFacts = settingsDataStore.personalFacts.first()

            if (apiKey.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    isLoadingSuggestions = false,
                    error = "API key not set. Go to Settings to add your Claude API key."
                )
                return@launch
            }

            // Try streaming first
            try {
                val editHistory = editHistoryRepository.getEditsForPrompt(currentState.contactAddress)
                _uiState.value = _uiState.value.copy(isStreaming = true)

                val fullText = StringBuilder()
                smsRepository.streamReplies(
                    messages = currentState.messages,
                    contactAddress = currentState.contactAddress,
                    contactName = currentState.contactName,
                    editHistory = editHistory,
                    apiKey = apiKey,
                    model = model,
                    tone = tone,
                    personalFacts = personalFacts
                ).collect { chunk ->
                    fullText.append(chunk)
                    _uiState.value = _uiState.value.copy(streamingText = fullText.toString())
                }

                val suggestions = promptBuilder.parseSuggestions(fullText.toString())
                _uiState.value = _uiState.value.copy(
                    suggestions = suggestions,
                    isLoadingSuggestions = false,
                    isStreaming = false,
                    streamingText = ""
                )
            } catch (e: Exception) {
                // Fall back to non-streaming
                _uiState.value = _uiState.value.copy(isStreaming = false, streamingText = "")

                val result = smsRepository.suggestReplies(
                    currentState.messages,
                    currentState.contactAddress,
                    currentState.contactName
                )

                _uiState.value = _uiState.value.copy(
                    isLoadingSuggestions = false,
                    suggestions = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun onSuggestionUsed(original: String, edited: String) {
        viewModelScope.launch {
            editHistoryRepository.saveEdit(
                contactAddress = _uiState.value.contactAddress,
                original = original,
                edited = edited
            )
        }
    }
}
