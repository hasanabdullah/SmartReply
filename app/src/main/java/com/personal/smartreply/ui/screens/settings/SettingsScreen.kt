package com.personal.smartreply.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.personal.smartreply.ui.components.PermissionHandler

private val AVAILABLE_MODELS = listOf(
    "claude-haiku-4-5" to "Haiku 4.5 (Fast, cheapest)",
    "claude-sonnet-4-6" to "Sonnet 4.6 (Balanced)",
    "claude-opus-4-6" to "Opus 4.6 (Most capable)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh permissions when screen resumes (user may return from system settings)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshOverlayPermission()
        }
    }

    PermissionHandler {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("SmartReply") })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Accessibility Service ──
                Text("Conversation Detection", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                if (state.isAccessibilityEnabled) {
                    Text(
                        "Active - SmartReply will auto-detect conversations in Google Messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "Enable the SmartReply accessibility service to auto-detect which conversation you're viewing in Google Messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable Accessibility Service")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── Overlay Controls ──
                Text("Overlay", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                if (!state.hasOverlayPermission) {
                    Text(
                        "SmartReply needs \"Display over other apps\" permission to show the suggestion bubble.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Overlay Permission")
                    }
                } else {
                    Button(
                        onClick = { viewModel.toggleOverlay() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.apiKey.isNotBlank(),
                        colors = if (state.isOverlayRunning) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (state.isOverlayRunning) "Stop Overlay" else "Start Overlay")
                    }
                    if (state.apiKey.isBlank()) {
                        Text(
                            "Set your API key below first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (!state.isOverlayRunning) {
                        Text(
                            if (state.isAccessibilityEnabled)
                                "The overlay will auto-appear when you open a conversation in Messages"
                            else
                                "Opens a floating bubble you can tap from any app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ── API Key ──
                Text("Claude API Key", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = { viewModel.updateApiKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-ant-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Model ──
                Text("Model", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                ModelSelector(
                    selectedModel = state.model,
                    onModelSelected = { viewModel.updateModel(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Tone ──
                Text("Tone Description", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.toneDescription,
                    onValueChange = { viewModel.updateToneDescription(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., casual, uses lowercase, rarely uses punctuation") },
                    minLines = 3,
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ── Test ──
                Button(
                    onClick = { viewModel.testConnection() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.apiKey.isNotBlank() && !state.isTesting
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Test Connection")
                    }
                }

                state.testResult?.let { result ->
                    Spacer(modifier = Modifier.height(8.dp))
                    val isError = result.startsWith("Error")
                    Text(
                        text = result,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    selectedModel: String,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = AVAILABLE_MODELS.find { it.first == selectedModel }?.second ?: selectedModel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AVAILABLE_MODELS.forEach { (modelId, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModelSelected(modelId)
                        expanded = false
                    }
                )
            }
        }
    }
}
