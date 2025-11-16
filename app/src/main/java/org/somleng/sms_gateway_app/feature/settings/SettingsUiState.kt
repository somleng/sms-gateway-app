package org.somleng.sms_gateway_app.feature.settings

data class SettingsUiState(
    val phoneNumber: String = "",
    val phoneNumberInput: String = "",
    val serverHost: String = "",
    val serverHostInput: String = "",
    val isSaving: Boolean = false,
    val isDev: Boolean = false,
) {
    val hasChanges: Boolean
        get() = phoneNumberInput != phoneNumber || (isDev && serverHostInput != serverHost)

    val isPhoneValid: Boolean
        get() = phoneNumberInput.isNotEmpty()

    val isServerHostValid: Boolean
        get() = if (isDev) {
            isValidWebsocketURL(serverHostInput)
        } else {
            true
        }

    val isValid: Boolean
        get() = isPhoneValid && isServerHostValid

    val canSave: Boolean
        get() = !isSaving && hasChanges && isValid

    private fun isValidWebsocketURL(url: String): Boolean {
        return url.matches(Regex("^wss?://[a-zA-Z0-9.-]+(:\\d+)?$"))
    }
}
