package org.somleng.sms_gateway_app.feature.settings

data class SettingsUiState(
    val phoneNumber: String = "",
    val savedPhoneNumber: String = "",
    val isSaving: Boolean = false,
) {
    val hasChanges: Boolean
        get() = phoneNumber != savedPhoneNumber

    val isValid: Boolean
        get() = phoneNumber.isNotEmpty()

    val canSave: Boolean
        get() = !isSaving && hasChanges && isValid

}

