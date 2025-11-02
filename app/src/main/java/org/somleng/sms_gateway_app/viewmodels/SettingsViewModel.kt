package org.somleng.sms_gateway_app.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettingsDataStore = AppSettingsDataStore(application.applicationContext)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsDataStore.phoneNumberFlow.collect { storedNumber ->
                val sanitized = storedNumber.orEmpty()
                _uiState.update { current ->
                    if (!current.isInitialized) {
                        current.copy(
                            phoneNumberInput = sanitized,
                            savedPhoneNumber = sanitized,
                            isInitialized = true
                        )
                    } else {
                        current.copy(
                            savedPhoneNumber = sanitized
                        )
                    }
                }
            }
        }
    }

    fun onPhoneNumberChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                phoneNumberInput = value,
                statusMessage = null
            )
        }
    }

    fun savePhoneNumber() {
        val trimmed = _uiState.value.phoneNumberInput.trim()
        viewModelScope.launch {
            try {
                _uiState.update { current ->
                    current.copy(
                        isSaving = true,
                        statusMessage = null
                    )
                }

                if (trimmed.isEmpty()) {
                    appSettingsDataStore.clearPhoneNumber()
                } else {
                    appSettingsDataStore.savePhoneNumber(trimmed)
                }

                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        savedPhoneNumber = trimmed,
                        phoneNumberInput = trimmed,
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save phone number", error)

                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        statusMessage = SettingsStatusMessage(
                            message = "Failed to save phone number",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}

data class SettingsUiState(
    val phoneNumberInput: String = "",
    val savedPhoneNumber: String = "",
    val isSaving: Boolean = false,
    val statusMessage: SettingsStatusMessage? = null,
    val isInitialized: Boolean = false
) {
    val canSave: Boolean
        get() = !isSaving && phoneNumberInput.trim() != savedPhoneNumber
}

data class SettingsStatusMessage(
    val message: String,
    val isError: Boolean
)
