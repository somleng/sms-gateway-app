package org.somleng.sms_gateway_app.feature.main

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
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore
import org.somleng.sms_gateway_app.services.ActionCableService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsDataStore = SettingsDataStore(appContext)
    private val actionCableService = ActionCableService.getInstance(appContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val heartbeatIntervalMs = 30_000L
    private var heartbeatJob: Job? = null

    private val autoConnectAttempted = AtomicBoolean(false)

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

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        cancelReconnect()
        actionCableService.disconnect()
    }

    fun onConnectClick(deviceKey: String) {
        if (_uiState.value.busy) return
        connect(deviceKey)
    }

    fun onDisconnectClick() {
        disconnect()
    }

    fun toggleReceiving(enabled: Boolean) {
        _uiState.update { it.copy(receivingEnabled = enabled) }
        viewModelScope.launch {
            settingsDataStore.setReceivingEnabled(enabled)
        }
    }

    fun toggleSending(enabled: Boolean) {
        _uiState.update { it.copy(sendingEnabled = enabled) }
        viewModelScope.launch {
            settingsDataStore.setSendingEnabled(enabled)
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
        _uiState.update { it.copy(isReconnecting = false) }
    }

    private fun connect(deviceKey: String) {
        val trimmedKey = deviceKey.trim()
        if (trimmedKey.isEmpty()) {
            updateStatus("Device key is required")
            return
        }

        allowReconnectFromStoredKey = true
        viewModelScope.launch {
            try {
                settingsDataStore.setDeviceKey(trimmedKey)
                cancelReconnect()

                _uiState.update { current ->
                    current.copy(
                        deviceKey = trimmedKey,
                        isAutoConnecting = false,
                        isReconnecting = false,
                        isConnected = false,
                        connectionStatusText = "Connecting..."
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

    private fun disconnect() {
        viewModelScope.launch {
            allowReconnectFromStoredKey = false
            cancelReconnect()
            actionCableService.disconnect()
            settingsDataStore.clearDeviceKey()

            _uiState.update { current ->
                current.copy(
                    deviceKey = null,
                    isConnected = false,
                    isAutoConnecting = false,
                    isReconnecting = false,
                    connectionStatusText = "Not configured"
                )
            }
        }
    }

    private fun observeStoredDeviceKey() {
        viewModelScope.launch {
            settingsDataStore.deviceKey.collect { storedKey ->
                _uiState.update { current ->
                    current.copy(
                        deviceKey = storedKey,
                        isConnected = storedKey != null && current.isConnected
                    )
                }
            }
        }
    }

    private fun observeMessageToggles() {
        viewModelScope.launch {
            combine(
                settingsDataStore.receivingEnabled,
                settingsDataStore.sendingEnabled
            ) { receiving, sending ->
                receiving to sending
            }.collect { (receiving, sending) ->
                _uiState.update { current ->
                    current.copy(
                        receivingEnabled = receiving,
                        sendingEnabled = sending
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
                            current.copy(
                                isConnected = !current.deviceKey.isNullOrBlank(),
                                isAutoConnecting = false,
                                isReconnecting = false,
                                connectionStatusText = "Online"
                            )
                        }
                        cancelReconnect()
                        startHeartbeat()
                    }
                    ActionCableService.ConnectionState.DISCONNECTED,
                    ActionCableService.ConnectionState.ERROR -> {
                        _uiState.update { current ->
                            val statusText = when {
                                current.isReconnecting -> "Reconnecting..."
                                current.deviceKey.isNullOrBlank() -> "Not configured"
                                state == ActionCableService.ConnectionState.ERROR -> "Connection Error"
                                else -> "Disconnected"
                            }
                            current.copy(
                                isConnected = false,
                                isAutoConnecting = false,
                                isReconnecting = false,
                                connectionStatusText = statusText
                            )
                        }
                        stopHeartbeat()
                        scheduleReconnectIfNeeded()
                    }
                    ActionCableService.ConnectionState.CONNECTING -> {
                        _uiState.update { current ->
                            current.copy(
                                isConnected = false,
                                connectionStatusText = "Connecting..."
                            )
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

        val storedDeviceKey = settingsDataStore.deviceKey.first()
        if (storedDeviceKey.isNullOrBlank()) return
        if (actionCableService.connectionState.value != ActionCableService.ConnectionState.DISCONNECTED) return

        if (autoConnectAttempted.compareAndSet(false, true)) {
            Log.d(TAG, "Auto-connecting with stored device key")
            _uiState.update { current ->
                current.copy(
                    isAutoConnecting = true,
                    connectionStatusText = "Reconnecting..."
                )
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
        _uiState.update { it.copy(isReconnecting = false) }
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

            val storedDeviceKey = settingsDataStore.deviceKey.first()
            if (storedDeviceKey.isNullOrBlank()) {
                hasPendingReconnect = false
                return@launch
            }

            hasPendingReconnect = false
            _uiState.update { current -> current.copy(isReconnecting = true, connectionStatusText = "Reconnecting...") }

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

                _uiState.update { it.copy(isReconnecting = false) }
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

    companion object {
        private const val TAG = "MainViewModel"
    }
}
