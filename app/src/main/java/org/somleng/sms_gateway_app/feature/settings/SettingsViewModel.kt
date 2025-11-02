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
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore
import org.somleng.sms_gateway_app.viewmodels.SettingsViewModel as ExistingSettingsViewModel

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    // Use existing SettingsViewModel internally to preserve business logic
    private val existingViewModel = ExistingSettingsViewModel(application)
    
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        // Observe existing viewmodel state
        viewModelScope.launch {
            existingViewModel.uiState.collect { existingState ->
                val clean = existingState.phoneNumberInput.filter { it.isDigit() || it == '+' }
                _ui.update {
                    SettingsUiState(
                        phoneNumber = clean,
                        isValid = clean.isNotEmpty(),
                        hasChanges = clean != existingState.savedPhoneNumber,
                        isSaving = existingState.isSaving,
                        savedPhoneNumber = existingState.savedPhoneNumber,
                        isInitialized = existingState.isInitialized
                    )
                }
            }
        }
    }

    fun onPhoneChange(text: String) {
        existingViewModel.onPhoneNumberChanged(text)
    }

    fun onSave() {
        if (_ui.value.isValid) {
            existingViewModel.savePhoneNumber()
        }
    }
}

