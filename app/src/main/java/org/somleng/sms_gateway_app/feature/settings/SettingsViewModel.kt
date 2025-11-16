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
                val sanitizedPhoneNumber = storedNumber.orEmpty()

                _uiState.update { current ->
                    current.copy(
                        phoneNumber = sanitizedPhoneNumber,
                        phoneNumberInput = sanitizedPhoneNumber,
                    )
                }
            }
        }
    }

    fun onPhoneChange(text: String) {
        val normalizedNumber = text.filter { it.isDigit() || it == '+' }

        _uiState.update { current ->
            current.copy(
                phoneNumberInput = normalizedNumber,
            )
        }
    }

    fun onSave() {
        val normalizedNumber = _uiState.value.phoneNumberInput.trim()
        if (!_uiState.value.isValid) return

        viewModelScope.launch {
            try {
                _uiState.update { current ->
                    current.copy(isSaving = true)
                }

                settingsDataStore.setPhoneNumber(normalizedNumber)

                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        phoneNumberInput = normalizedNumber,
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
