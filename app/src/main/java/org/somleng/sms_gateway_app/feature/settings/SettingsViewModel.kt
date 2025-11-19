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
import org.somleng.sms_gateway_app.BuildConfig
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsDataStore = SettingsDataStore(application.applicationContext)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            isDev = BuildConfig.ENVIRONMENT == "dev"
        )
    )
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

        viewModelScope.launch {
            settingsDataStore.serverHost.collect { storedHost ->
                val serverHost = storedHost ?: BuildConfig.SOMLENG_WS_URL

                _uiState.update { current ->
                    current.copy(
                        serverHost = serverHost,
                        serverHostInput = serverHost,
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

    fun onServerChange(text: String) {
        _uiState.update { current ->
            current.copy(
                serverHostInput = text.trim(),
            )
        }
    }

    fun onSave() {
        val normalizedNumber = _uiState.value.phoneNumberInput.trim()
        val serverHostInput = _uiState.value.serverHostInput.trim()
        if (!_uiState.value.isValid) return

        viewModelScope.launch {
            try {
                _uiState.update { current ->
                    current.copy(isSaving = true)
                }

                settingsDataStore.setPhoneNumber(normalizedNumber)

                // Only save server host in dev builds
                if (_uiState.value.isDev && serverHostInput.isNotEmpty()) {
                    settingsDataStore.setServerHost(serverHostInput)
                }

                _uiState.update { current ->
                    current.copy(
                        isSaving = false,
                        phoneNumberInput = normalizedNumber,
                        serverHostInput = serverHostInput,
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save settings", error)
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
