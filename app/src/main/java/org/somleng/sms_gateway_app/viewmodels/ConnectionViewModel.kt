package org.somleng.sms_gateway_app.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore
import org.somleng.sms_gateway_app.services.ActionCableService

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val appSettingsDataStore = AppSettingsDataStore(appContext)
    private val actionCableService = ActionCableService.getInstance(appContext)

    private val heartbeatIntervalMs = 30_000L
    private var heartbeatJob: Job? = null

    private val autoConnectAttempted = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private fun updateUiState(
        state: ActionCableService.ConnectionState? = null,
        transform: (ConnectionUiState) -> ConnectionUiState
    ) {
        _uiState.update { current ->
            val baseline = state?.let { current.copy(connectionState = it) } ?: current
            val updated = transform(baseline)
            val effectiveState = state ?: updated.connectionState
            updated.withStatusFor(effectiveState)
        }
    }

    private var reconnectJob: Job? = null
    private val reconnectBaseDelayMs = 3_000L
    private val reconnectMaxDelayMs = 15_000L

    @Volatile private var isAppInForeground = true
    @Volatile private var hasPendingReconnect = false
    @Volatile private var allowReconnectFromStoredKey = true

    init {
        observeConnectionState()
        observeStoredDeviceKey()
        observeMessageToggles()

        viewModelScope.launch {
            attemptAutoConnect()
        }
    }

    fun connect(deviceKey: String) {
        val trimmedKey = deviceKey.trim()
        if (trimmedKey.isEmpty()) {
            updateStatus("Device key is required")
            return
        }

        allowReconnectFromStoredKey = true
        viewModelScope.launch {
            try {
                appSettingsDataStore.saveDeviceKey(trimmedKey)
                cancelReconnect()

                updateUiState(ActionCableService.ConnectionState.CONNECTING) { current ->
                    current.copy(
                        deviceKey = trimmedKey,
                        isAutoConnecting = false,
                        isReconnecting = false,
                        isConnected = false
                    )
                }

                actionCableService.connect(trimmedKey)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Connection failed", error)
                updateStatus("Connection failed: ${error.message ?: "Unknown error"}")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            allowReconnectFromStoredKey = false
            cancelReconnect()
            actionCableService.disconnect()
            appSettingsDataStore.clearDeviceKey()

            updateUiState(ActionCableService.ConnectionState.DISCONNECTED) { current ->
                current.copy(
                    deviceKey = null,
                    isConnected = false,
                    isAutoConnecting = false,
                    isReconnecting = false
                )
            }
        }
    }

    fun toggleReceiving(enabled: Boolean) {
        updateUiState { it.copy(isReceivingEnabled = enabled) }
        viewModelScope.launch {
            appSettingsDataStore.setReceivingEnabled(enabled)
        }
    }

    fun toggleSending(enabled: Boolean) {
        updateUiState { it.copy(isSendingEnabled = enabled) }
        viewModelScope.launch {
            appSettingsDataStore.setSendingEnabled(enabled)
        }
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        if (hasPendingReconnect || shouldAttemptReconnectNow()) {
            scheduleReconnectIfNeeded(force = true)
        }
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        if (reconnectJob?.isActive == true) {
            reconnectJob?.cancel()
            hasPendingReconnect = true
        }
        updateUiState { it.copy(isReconnecting = false) }
    }

    private fun observeStoredDeviceKey() {
        viewModelScope.launch {
            appSettingsDataStore.deviceKeyFlow.collect { storedKey ->
                updateUiState { current ->
                    current.copy(
                        deviceKey = storedKey,
                        isConnected = storedKey != null && current.connectionState == ActionCableService.ConnectionState.CONNECTED
                    )
                }
            }
        }
    }

    private fun observeMessageToggles() {
        viewModelScope.launch {
            combine(
                appSettingsDataStore.receivingEnabledFlow,
                appSettingsDataStore.sendingEnabledFlow
            ) { receiving, sending ->
                receiving to sending
            }.collect { (receiving, sending) ->
                updateUiState { current ->
                    current.copy(
                        isReceivingEnabled = receiving,
                        isSendingEnabled = sending
                    )
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            actionCableService.connectionState.collect { state ->
                when (state) {
                    ActionCableService.ConnectionState.CONNECTED -> {
                        updateUiState(state) { current ->
                            current.copy(
                                isConnected = !current.deviceKey.isNullOrBlank(),
                                isAutoConnecting = false,
                                isReconnecting = false
                            )
                        }
                        cancelReconnect()
                        startHeartbeat()
                    }
                    ActionCableService.ConnectionState.DISCONNECTED,
                    ActionCableService.ConnectionState.ERROR -> {
                        updateUiState(state) { current ->
                            current.copy(
                                isConnected = false,
                                isAutoConnecting = false,
                                isReconnecting = false
                            )
                        }
                        stopHeartbeat()
                        scheduleReconnectIfNeeded()
                    }
                    ActionCableService.ConnectionState.CONNECTING -> {
                        updateUiState(state) { current ->
                            current.copy(isConnected = false)
                        }
                        stopHeartbeat()
                    }
                }
            }
        }
    }

    private suspend fun attemptAutoConnect() {
        if (autoConnectAttempted.get()) return
        if (!allowReconnectFromStoredKey) return

        val storedDeviceKey = appSettingsDataStore.deviceKeyFlow.first()
        if (storedDeviceKey.isNullOrBlank()) return
        if (actionCableService.connectionState.value != ActionCableService.ConnectionState.DISCONNECTED) return

        if (autoConnectAttempted.compareAndSet(false, true)) {
            Log.d(TAG, "Auto-connecting with stored device key")
            updateUiState(ActionCableService.ConnectionState.CONNECTING) { current ->
                current.copy(isAutoConnecting = true)
            }
            try {
                actionCableService.connect(storedDeviceKey)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Auto-connect failed", error)
                updateStatus("Auto-connect failed: ${error.message ?: "Unknown error"}")
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()

        heartbeatJob = viewModelScope.launch {
            while (actionCableService.isConnected() && isActive) {
                delay(heartbeatIntervalMs)
                if (actionCableService.isConnected()) {
                    actionCableService.sendHeartbeat()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        hasPendingReconnect = false
        updateUiState { it.copy(isReconnecting = false) }
    }

    private fun scheduleReconnectIfNeeded(force: Boolean = false) {
        if (!force && !isAppInForeground) {
            hasPendingReconnect = true
            return
        }

        if (!allowReconnectFromStoredKey) {
            hasPendingReconnect = false
            return
        }

        val state = actionCableService.connectionState.value
        if (state == ActionCableService.ConnectionState.CONNECTED || state == ActionCableService.ConnectionState.CONNECTING) {
            return
        }

        if (reconnectJob?.isActive == true) return

        reconnectJob = viewModelScope.launch {
            if (!allowReconnectFromStoredKey) {
                hasPendingReconnect = false
                return@launch
            }

            val storedDeviceKey = appSettingsDataStore.deviceKeyFlow.first()
            if (storedDeviceKey.isNullOrBlank()) {
                hasPendingReconnect = false
                return@launch
            }

            hasPendingReconnect = false
            updateUiState { current -> current.copy(isReconnecting = true) }

            try {
                var attempt = 0
                while (isActive) {
                    if (!allowReconnectFromStoredKey) {
                        hasPendingReconnect = false
                        return@launch
                    }

                    if (!isAppInForeground) {
                        hasPendingReconnect = true
                        return@launch
                    }

                    val currentState = actionCableService.connectionState.value
                    if (currentState == ActionCableService.ConnectionState.CONNECTED) return@launch

                    if (currentState != ActionCableService.ConnectionState.CONNECTING) {
                        try {
                            actionCableService.connect(storedDeviceKey)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Log.e(TAG, "Reconnect attempt failed", error)
                        }
                    }

                    attempt++
                    val delayMillis = (reconnectBaseDelayMs * attempt).coerceAtMost(reconnectMaxDelayMs)
                    delay(delayMillis)
                }
            } finally {
                if (!isAppInForeground) {
                    hasPendingReconnect = true
                }

                updateUiState { it.copy(isReconnecting = false) }
            }
        }.also { job ->
            job.invokeOnCompletion {
                reconnectJob = null
            }
        }
    }

    private fun shouldAttemptReconnectNow(): Boolean {
        return when (actionCableService.connectionState.value) {
            ActionCableService.ConnectionState.DISCONNECTED,
            ActionCableService.ConnectionState.ERROR -> true
            else -> false
        }
    }

    private fun updateStatus(message: String) {
        updateUiState { it.copy(connectionStatusText = message) }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        cancelReconnect()
        actionCableService.disconnect()
    }

    private fun ConnectionUiState.withStatusFor(state: ActionCableService.ConnectionState): ConnectionUiState {
        val status = when {
            isReconnecting -> "Reconnecting..."
            isAutoConnecting && state != ActionCableService.ConnectionState.CONNECTED -> "Reconnecting..."
            deviceKey.isNullOrBlank() -> "Not configured"
            state == ActionCableService.ConnectionState.CONNECTING -> "Connecting..."
            state == ActionCableService.ConnectionState.CONNECTED -> "Connected"
            state == ActionCableService.ConnectionState.DISCONNECTED && !deviceKey.isNullOrBlank() -> "Disconnected"
            state == ActionCableService.ConnectionState.ERROR -> "Connection Error"
            else -> "Not configured"
        }

        return copy(
            connectionStatusText = status
        )
    }

    companion object {
        private const val TAG = "ConnectionViewModel"
    }
}

data class ConnectionUiState(
    val deviceKey: String? = null,
    val isConnected: Boolean = false,
    val connectionState: ActionCableService.ConnectionState = ActionCableService.ConnectionState.DISCONNECTED,
    val isReceivingEnabled: Boolean = true,
    val isSendingEnabled: Boolean = true,
    val isAutoConnecting: Boolean = false,
    val isReconnecting: Boolean = false,
    val connectionStatusText: String = "Not configured"
) {
    val canDisconnect: Boolean
        get() = !deviceKey.isNullOrBlank()

    val isConnecting: Boolean
        get() = connectionState == ActionCableService.ConnectionState.CONNECTING
}
