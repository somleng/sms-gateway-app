package org.somleng.sms_gateway_app.feature.settings

data class SettingsUiState(
    val phoneNumber: String = "",
    val isValid: Boolean = false,
    val hasChanges: Boolean = false,
    val isSaving: Boolean = false,
    val savedPhoneNumber: String = "",
    val isInitialized: Boolean = false
) {
    val canSave: Boolean
        get() = !isSaving && hasChanges && isValid
}

