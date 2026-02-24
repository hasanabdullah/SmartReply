package com.personal.smartreply.ui.screens.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.smartreply.data.sms.SmsThread
import com.personal.smartreply.repository.SmsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationsUiState(
    val threads: List<SmsThread> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val smsRepository: SmsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    fun loadThreads() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val threads = smsRepository.getThreads()
                _uiState.value = ConversationsUiState(threads = threads, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = ConversationsUiState(
                    isLoading = false,
                    error = e.message ?: "Failed to load messages"
                )
            }
        }
    }
}
