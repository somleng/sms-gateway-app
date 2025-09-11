package org.somleng.sms_gateway_app.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore
import org.somleng.sms_gateway_app.services.ActionCableService

class ConnectionViewModel(private val context: Context) : ViewModel() {

    private val appSettingsDataStore = AppSettingsDataStore(context)
    private val actionCableService = ActionCableService(context)

    // Heartbeat job for periodic connection testing
    private var heartbeatJob: Job? = null
    private val heartbeatIntervalMs = 30000L // 30 seconds

    // Auto-connect flag to prevent repeated attempts on startup
    private var autoConnectAttempted = false

    // Track if we're currently auto-connecting to hide device key from UI
    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    // UI State
    private val _isReceivingEnabled = MutableStateFlow(true)
    val isReceivingEnabled: StateFlow<Boolean> = _isReceivingEnabled.asStateFlow()

    private val _isSendingEnabled = MutableStateFlow(true)
    val isSendingEnabled: StateFlow<Boolean> = _isSendingEnabled.asStateFlow()

    private val _connectionStatus = MutableStateFlow("")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // Combined state for connection status
    val connectionState = combine(
        appSettingsDataStore.deviceKeyFlow,
        actionCableService.connectionState,
        _isReceivingEnabled,
        _isSendingEnabled,
        _isAutoConnecting
    ) { deviceKey, cableState, receiving, sending, isAutoConnecting ->
        ConnectionUiState(
            deviceKey = deviceKey,
            isConnected = deviceKey != null && cableState == ActionCableService.ConnectionState.CONNECTED,
            connectionState = cableState,
            isReceivingEnabled = receiving,
            isSendingEnabled = sending,
            isAutoConnecting = isAutoConnecting,
            connectionStatusText = when (cableState) {
                ActionCableService.ConnectionState.CONNECTING -> if (isAutoConnecting) "Auto-connecting to Somleng..." else "Connecting to Somleng..."
                ActionCableService.ConnectionState.CONNECTED -> "Connected to Somleng"
                ActionCableService.ConnectionState.DISCONNECTED -> if (deviceKey != null) "Disconnected" else "Not configured"
                ActionCableService.ConnectionState.ERROR -> "Connection error"
            }
        )
    }

    init {
        // Monitor ActionCable connection state
        viewModelScope.launch {
            actionCableService.connectionState.collect { state ->
                _connectionStatus.value = actionCableService.getConnectionStatus()

                // Reset auto-connecting state when connection completes or fails
                when (state) {
                    ActionCableService.ConnectionState.CONNECTED -> {
                        _isAutoConnecting.value = false
                        startHeartbeat()
                    }
                    ActionCableService.ConnectionState.DISCONNECTED,
                    ActionCableService.ConnectionState.ERROR -> {
                        _isAutoConnecting.value = false
                        stopHeartbeat()
                    }
                    ActionCableService.ConnectionState.CONNECTING -> {
                        stopHeartbeat()
                    }
                }
            }
        }

        // Auto-connect on startup if device key exists
        viewModelScope.launch {
            attemptAutoConnect()
        }
    }

    /**
     * Connect to Somleng using stored device key
     */
    fun connect(deviceKey: String) {
        viewModelScope.launch {
            try {
                // Save the device key
                appSettingsDataStore.saveDeviceKey(deviceKey)

                // Connect to ActionCable
                actionCableService.connect(deviceKey)

            } catch (e: Exception) {
                // Handle connection error
                _connectionStatus.value = "Connection failed: ${e.message}"
            }
        }
    }

    /**
     * Disconnect from Somleng
     */
    fun disconnect() {
        viewModelScope.launch {
            // Disconnect from ActionCable
            actionCableService.disconnect()

            // Clear stored device key
            appSettingsDataStore.clearDeviceKey()

            _connectionStatus.value = "Disconnected"
        }
    }

    /**
     * Toggle receiving SMS functionality
     */
    fun toggleReceiving(enabled: Boolean) {
        _isReceivingEnabled.value = enabled
        // You might want to inform the server about this state change
        // actionCableService.updateReceivingStatus(enabled)
    }

    /**
     * Toggle sending SMS functionality
     */
    fun toggleSending(enabled: Boolean) {
        _isSendingEnabled.value = enabled
        // You might want to inform the server about this state change
        // actionCableService.updateSendingStatus(enabled)
    }

    /**
     * Send a test heartbeat to check connection
     */
    fun sendHeartbeat() {
        actionCableService.sendHeartbeat()
    }

    /**
     * Check if ActionCable is currently connected
     */
    fun isActionCableConnected(): Boolean {
        return actionCableService.isConnected()
    }

    /**
     * Attempt auto-connect on startup if device key exists
     */
    private suspend fun attemptAutoConnect() {
        try {
            // Prevent repeated auto-connect attempts
            if (autoConnectAttempted) return
            autoConnectAttempted = true

            // Get the stored device key
            val storedDeviceKey = appSettingsDataStore.deviceKeyFlow.first()

            // Only auto-connect if we have a device key and aren't already connected/connecting
            if (!storedDeviceKey.isNullOrBlank() &&
                actionCableService.connectionState.value == ActionCableService.ConnectionState.DISCONNECTED) {

                Log.d("ConnectionViewModel", "Auto-connecting with stored device key")
                _isAutoConnecting.value = true
                actionCableService.connect(storedDeviceKey)
            }
        } catch (e: Exception) {
            Log.e("ConnectionViewModel", "Error during auto-connect", e)
            _isAutoConnecting.value = false
            _connectionStatus.value = "Auto-connect failed: ${e.message}"
        }
    }

    /**
     * Start periodic heartbeat when connected
     */
    private fun startHeartbeat() {
        // Cancel existing heartbeat if running
        stopHeartbeat()

        heartbeatJob = viewModelScope.launch {
            while (actionCableService.isConnected()) {
                delay(heartbeatIntervalMs)

                // Check if still connected before sending heartbeat
                if (actionCableService.isConnected()) {
                    actionCableService.sendHeartbeat()
                }
            }
        }
    }

    /**
     * Stop periodic heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        actionCableService.disconnect()
    }
}

/**
 * UI State data class
 */
data class ConnectionUiState(
    val deviceKey: String? = null,
    val isConnected: Boolean = false,
    val connectionState: ActionCableService.ConnectionState = ActionCableService.ConnectionState.DISCONNECTED,
    val isReceivingEnabled: Boolean = true,
    val isSendingEnabled: Boolean = true,
    val isAutoConnecting: Boolean = false,
    val connectionStatusText: String = "Not connected"
)
