package org.somleng.sms_gateway_app.feature.main

sealed class ConnectionPhase {
    object Idle : ConnectionPhase()
    data class Connecting(val attemptNumber: Int = 0) : ConnectionPhase()
    object Connected : ConnectionPhase()
    object Failed : ConnectionPhase()
}

data class MainUiState(
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val receivingEnabled: Boolean = true,
    val sendingEnabled: Boolean = true,
    val statusMessage: String = "Not configured",
    val deviceKey: String? = null
) {
    val isConnected: Boolean
        get() = connectionPhase is ConnectionPhase.Connected

    val isConnecting: Boolean
        get() = connectionPhase is ConnectionPhase.Connecting

    val showDeviceKeyForm: Boolean
        get() = connectionPhase is ConnectionPhase.Idle || connectionPhase is ConnectionPhase.Failed

    val canDisconnect: Boolean
        get() = deviceKey != null

    val showConnectingView: Boolean
        get() = connectionPhase is ConnectionPhase.Connecting

    val showConnectedView: Boolean
        get() = connectionPhase is ConnectionPhase.Connected
}

