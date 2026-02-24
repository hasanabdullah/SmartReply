package com.personal.smartreply.ui.screens.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.smartreply.data.local.SettingsDataStore
import com.personal.smartreply.repository.SmsRepository
import com.personal.smartreply.service.OverlayService
import com.personal.smartreply.service.SmartReplyAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val model: String = "claude-haiku-4-5",
    val toneDescription: String = "",
    val testResult: String? = null,
    val isTesting: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val isOverlayRunning: Boolean = false,
    val isAccessibilityEnabled: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val smsRepository: SmsRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.apiKey.collect { key ->
                _uiState.value = _uiState.value.copy(apiKey = key)
            }
        }
        viewModelScope.launch {
            settingsDataStore.model.collect { model ->
                _uiState.value = _uiState.value.copy(model = model)
            }
        }
        viewModelScope.launch {
            settingsDataStore.toneDescription.collect { tone ->
                _uiState.value = _uiState.value.copy(toneDescription = tone)
            }
        }
    }

    fun refreshOverlayPermission() {
        _uiState.value = _uiState.value.copy(
            hasOverlayPermission = Settings.canDrawOverlays(appContext),
            isAccessibilityEnabled = isAccessibilityServiceEnabled()
        )
    }

    fun toggleOverlay() {
        val isRunning = _uiState.value.isOverlayRunning
        if (isRunning) {
            appContext.stopService(Intent(appContext, OverlayService::class.java))
            _uiState.value = _uiState.value.copy(isOverlayRunning = false)
        } else {
            appContext.startForegroundService(Intent(appContext, OverlayService::class.java))
            _uiState.value = _uiState.value.copy(isOverlayRunning = true)
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch { settingsDataStore.setApiKey(key) }
    }

    fun updateModel(model: String) {
        viewModelScope.launch { settingsDataStore.setModel(model) }
    }

    fun updateToneDescription(tone: String) {
        viewModelScope.launch { settingsDataStore.setToneDescription(tone) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)
            val result = smsRepository.testConnection()
            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResult = result.fold(
                    onSuccess = { "Connected! Response: $it" },
                    onFailure = { "Error: ${it.message}" }
                )
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.name == SmartReplyAccessibilityService::class.java.name
        }
    }
}
