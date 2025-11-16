package org.somleng.sms_gateway_app.feature.settings

data class SettingsUiState(
    val phoneNumber: String = "",
    val phoneNumberInput: String = "",
    val isSaving: Boolean = false,
) {
    val hasChanges: Boolean
        get() = phoneNumberInput != phoneNumber

    val isValid: Boolean
        get() = phoneNumberInput.isNotEmpty()

    val canSave: Boolean
        get() = !isSaving && hasChanges && isValid

}
