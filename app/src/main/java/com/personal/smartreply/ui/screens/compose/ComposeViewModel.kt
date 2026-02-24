package com.personal.smartreply.ui.screens.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.smartreply.data.contacts.Contact
import com.personal.smartreply.data.contacts.ContactsReader
import com.personal.smartreply.repository.SmsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComposeUiState(
    val allContacts: List<Contact> = emptyList(),
    val filteredContacts: List<Contact> = emptyList(),
    val searchQuery: String = "",
    val selectedContact: Contact? = null,
    val contextDescription: String = "",
    val suggestions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val contactsReader: ContactsReader,
    private val smsRepository: SmsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contacts = contactsReader.getAllContacts()
                _uiState.value = _uiState.value.copy(
                    allContacts = contacts,
                    filteredContacts = contacts
                )
            } catch (_: Exception) {
                // Contacts may not be available
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            selectedContact = null,
            filteredContacts = if (query.isBlank()) {
                _uiState.value.allContacts
            } else {
                _uiState.value.allContacts.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.phoneNumber.contains(query)
                }
            }
        )
    }

    fun selectContact(contact: Contact) {
        _uiState.value = _uiState.value.copy(
            selectedContact = contact,
            searchQuery = contact.name,
            filteredContacts = emptyList()
        )
    }

    fun updateContext(context: String) {
        _uiState.value = _uiState.value.copy(contextDescription = context)
    }

    fun suggestOpening() {
        val contact = _uiState.value.selectedContact ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                suggestions = emptyList(),
                error = null
            )

            val result = smsRepository.suggestOpening(
                contactName = contact.name,
                context = _uiState.value.contextDescription.ifBlank { null }
            )

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                suggestions = result.getOrDefault(emptyList()),
                error = result.exceptionOrNull()?.message
            )
        }
    }
}
