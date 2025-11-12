package org.somleng.sms_gateway_app.feature.main

data class MainUiState(
    val isConnected: Boolean = false,
    val receivingEnabled: Boolean = true,
    val sendingEnabled: Boolean = true,
    val connectionStatusText: String = "Not configured",
    val deviceKey: String? = null,
    val isAutoConnecting: Boolean = false,
    val isReconnecting: Boolean = false
) {
    val canDisconnect: Boolean
        get() = deviceKey != null

    val busy: Boolean
        get() = isAutoConnecting || isReconnecting || connectionStatusText == "Connecting..."

    val isConnecting: Boolean
        get() = busy && !isConnected
}

