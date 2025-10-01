package org.somleng.sms_gateway_app.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
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

class ConnectionViewModel(private val context: Context) : ViewModel() {

    private val appSettingsDataStore = AppSettingsDataStore(context)
    private val actionCableService = ActionCableService(context)

    private val heartbeatIntervalMs = 30_000L
    private var heartbeatJob: Job? = null

    private val autoConnectAttempted = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private var reconnectJob: Job? = null
    private val reconnectBaseDelayMs = 3_000L
    private val reconnectMaxDelayMs = 15_000L

    @Volatile private var isAppInForeground = true
    @Volatile private var hasPendingReconnect = false

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

        viewModelScope.launch {
            runCatching {
                appSettingsDataStore.saveDeviceKey(trimmedKey)
                cancelReconnect()

                _uiState.update { current ->
                    val next = current.copy(
                        deviceKey = trimmedKey,
                        isAutoConnecting = false,
                        isReconnecting = false,
                        connectionState = ActionCableService.ConnectionState.CONNECTING
                    )
                    next.withStatusFor(next.connectionState)
                }

                actionCableService.connect(trimmedKey)
            }.onFailure { error ->
                updateStatus("Connection failed: ${error.message ?: "Unknown error"}")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            cancelReconnect()
            actionCableService.disconnect()
            appSettingsDataStore.clearDeviceKey()

            _uiState.update { current ->
                val next = current.copy(
                    deviceKey = null,
                    isConnected = false,
                    isAutoConnecting = false,
                    isReconnecting = false,
                    connectionState = ActionCableService.ConnectionState.DISCONNECTED
                )
                next.withStatusFor(next.connectionState)
            }
        }
    }

    fun toggleReceiving(enabled: Boolean) {
        _uiState.update { it.copy(isReceivingEnabled = enabled) }
        viewModelScope.launch {
            appSettingsDataStore.setReceivingEnabled(enabled)
        }
    }

    fun toggleSending(enabled: Boolean) {
        _uiState.update { it.copy(isSendingEnabled = enabled) }
        viewModelScope.launch {
            appSettingsDataStore.setSendingEnabled(enabled)
        }
    }

    fun sendHeartbeat() {
        actionCableService.sendHeartbeat()
    }

    fun isActionCableConnected(): Boolean = actionCableService.isConnected()

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
        _uiState.update { it.copy(isReconnecting = false).withStatusFor(it.connectionState) }
    }

    private fun observeStoredDeviceKey() {
        viewModelScope.launch {
            appSettingsDataStore.deviceKeyFlow.collect { storedKey ->
                _uiState.update { current ->
                    val next = current.copy(
                        deviceKey = storedKey,
                        isConnected = storedKey != null && current.connectionState == ActionCableService.ConnectionState.CONNECTED
                    )
                    next.withStatusFor(current.connectionState)
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
                _uiState.update { current ->
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
                        _uiState.update { current ->
                            val next = current.copy(
                                connectionState = state,
                                isConnected = !current.deviceKey.isNullOrBlank(),
                                isAutoConnecting = false,
                                isReconnecting = false
                            )
                            next.withStatusFor(state)
                        }
                        cancelReconnect()
                        startHeartbeat()
                    }
                    ActionCableService.ConnectionState.DISCONNECTED,
                    ActionCableService.ConnectionState.ERROR -> {
                        _uiState.update { current ->
                            val next = current.copy(
                                connectionState = state,
                                isConnected = false,
                                isAutoConnecting = false
                            )
                            next.withStatusFor(state)
                        }
                        stopHeartbeat()
                        scheduleReconnectIfNeeded()
                    }
                    ActionCableService.ConnectionState.CONNECTING -> {
                        _uiState.update { current ->
                            val next = current.copy(
                                connectionState = state,
                                isConnected = false
                            )
                            next.withStatusFor(state)
                        }
                        stopHeartbeat()
                    }
                }
            }
        }
    }

    private suspend fun attemptAutoConnect() {
        if (autoConnectAttempted.get()) return

        val storedDeviceKey = appSettingsDataStore.deviceKeyFlow.first()
        if (storedDeviceKey.isNullOrBlank()) return
        if (actionCableService.connectionState.value != ActionCableService.ConnectionState.DISCONNECTED) return

        if (autoConnectAttempted.compareAndSet(false, true)) {
            Log.d(TAG, "Auto-connecting with stored device key")
            _uiState.update { current ->
                val next = current.copy(
                    isAutoConnecting = true,
                    connectionState = ActionCableService.ConnectionState.CONNECTING
                )
                next.withStatusFor(next.connectionState)
            }
            actionCableService.connect(storedDeviceKey)
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
        _uiState.update { current ->
            val next = current.copy(isReconnecting = false)
            next.withStatusFor(current.connectionState)
        }
    }

    private fun scheduleReconnectIfNeeded(force: Boolean = false) {
        if (!force && !isAppInForeground) {
            hasPendingReconnect = true
            return
        }

        val state = actionCableService.connectionState.value
        if (state == ActionCableService.ConnectionState.CONNECTED || state == ActionCableService.ConnectionState.CONNECTING) {
            return
        }

        if (reconnectJob?.isActive == true) return

        reconnectJob = viewModelScope.launch {
            val storedDeviceKey = appSettingsDataStore.deviceKeyFlow.first()
            if (storedDeviceKey.isNullOrBlank()) {
                hasPendingReconnect = false
                return@launch
            }

            hasPendingReconnect = false
            _uiState.update { current ->
                val next = current.copy(isReconnecting = true)
                next.withStatusFor(current.connectionState)
            }

            try {
                var attempt = 0
                while (isActive) {
                    if (!isAppInForeground) {
                        hasPendingReconnect = true
                        return@launch
                    }

                    val currentState = actionCableService.connectionState.value
                    if (currentState == ActionCableService.ConnectionState.CONNECTED) return@launch

                    if (currentState != ActionCableService.ConnectionState.CONNECTING) {
                        runCatching { actionCableService.connect(storedDeviceKey) }
                            .onFailure { error ->
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

                _uiState.update { current ->
                    val next = current.copy(isReconnecting = false)
                    next.withStatusFor(current.connectionState)
                }
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
        _uiState.update { it.copy(connectionStatusText = message) }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        cancelReconnect()
        actionCableService.disconnect()
    }

    private fun ConnectionUiState.withStatusFor(state: ActionCableService.ConnectionState): ConnectionUiState {
        val status = when {
            isReconnecting -> "Reconnecting to Somleng..."
            isAutoConnecting && state != ActionCableService.ConnectionState.CONNECTED -> "Auto-connecting to Somleng..."
            state == ActionCableService.ConnectionState.CONNECTING -> "Connecting to Somleng..."
            state == ActionCableService.ConnectionState.CONNECTED -> "Connected to Somleng"
            state == ActionCableService.ConnectionState.DISCONNECTED && !deviceKey.isNullOrBlank() -> "Disconnected"
            state == ActionCableService.ConnectionState.ERROR -> "Connection error"
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
)
