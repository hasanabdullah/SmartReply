package com.personal.smartreply.ui.screens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.personal.smartreply.ui.components.SuggestionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Message") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Contact search
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                label = { Text("Contact") },
                placeholder = { Text("Search by name or number") },
                singleLine = true
            )

            // Contact suggestions dropdown
            if (state.filteredContacts.isNotEmpty() && state.selectedContact == null && state.searchQuery.isNotBlank()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    items(state.filteredContacts.take(10)) { contact ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectContact(contact) }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = contact.phoneNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Context field
            OutlinedTextField(
                value = state.contextDescription,
                onValueChange = { viewModel.updateContext(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                label = { Text("Context (optional)") },
                placeholder = { Text("e.g., coworker, friend from college, neighbor") },
                minLines = 2,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Suggest button
            Button(
                onClick = { viewModel.suggestOpening() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = state.selectedContact != null && !state.isLoading
            ) {
                Text("Suggest Opening Message")
            }

            // Loading
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Error
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Suggestions
            if (state.suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                state.suggestions.forEachIndexed { index, suggestion ->
                    SuggestionCard(
                        index = index,
                        suggestion = suggestion,
                        onUse = { _, _ -> /* No edit history for compose since no contact address yet */ }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
