package org.somleng.sms_gateway_app.feature.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsDataStore = SettingsDataStore(application.applicationContext)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.phoneNumber.collect { storedNumber ->
                val sanitized = storedNumber.orEmpty()
                _uiState.update { current ->
                    if (!current.isInitialized) {
                        current.copy(
                            phoneNumber = sanitized,
                            savedPhoneNumber = sanitized,
                            isValid = sanitized.isNotEmpty(),
                            isInitialized = true
                        )
                    } else {
                        val hasChanges = sanitized != current.phoneNumber
                        current.copy(
                            savedPhoneNumber = sanitized,
                            hasChanges = hasChanges,
                            isValid = current.phoneNumber.isNotEmpty()
                        )
                    }
                }
            }
        }
    }

    fun onPhoneChange(text: String) {
        val clean = text.filter { it.isDigit() || it == '+' }
        _uiState.update { current ->
            current.copy(
                phoneNumber = clean,
                isValid = clean.isNotEmpty(),
                hasChanges = clean != current.savedPhoneNumber
            )
        }
    }

    fun onSave() {
        val trimmed = _uiState.value.phoneNumber.trim()
        if (!_uiState.value.isValid) return

        viewModelScope.launch {
            try {
                _uiState.update { current ->
                    current.copy(isSaving = true)
                }

                if (trimmed.isEmpty()) {
                    settingsDataStore.clearPhoneNumber()
                } else {
                    settingsDataStore.setPhoneNumber(trimmed)
                }

                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        savedPhoneNumber = trimmed,
                        phoneNumber = trimmed,
                        hasChanges = false
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save phone number", error)
                _uiState.update { current ->
                    current.copy(isSaving = false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
