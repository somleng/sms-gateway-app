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

    private val heartbeatIntervalMs = 10_000L
    private var heartbeatJob: Job? = null
    private var connectJob: Job? = null
    private val reconnectBaseDelayMs = 3_000L
    private val reconnectMaxDelayMs = 15_000L
    private val maxRetryAttempts = 3

    private val manualDisconnect = AtomicBoolean(false)

    init {
        observeConnectionState()
        observeStoredDeviceKey()
        observeMessageToggles()
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
        disconnect()
    }

    fun onConnectClick(deviceKey: String) {
        if (_uiState.value.isConnecting) return
        connect(deviceKey)
    }

    fun onDisconnectClick() {
        manualDisconnect.set(true)
        viewModelScope.launch {
            disconnect()

            _uiState.update {
                it.copy(
                    connectionPhase = ConnectionPhase.Idle,
                )
            }
        }
    }

    fun onDeviceKeyInputChange(text: String) {
        _uiState.update { it.copy(deviceKeyInput = text) }
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

    private fun connect(deviceKey: String) {
        manualDisconnect.set(false)
        val trimmedKey = deviceKey.trim()

        viewModelScope.launch {
            settingsDataStore.setDeviceKey(trimmedKey)
            _uiState.update {
                it.copy(
                    deviceKey = trimmedKey,
                    deviceKeyInput = trimmedKey,
                    connectionPhase = ConnectionPhase.Connecting(0),
                )
            }
            connectWithRetry(trimmedKey, maxRetryAttempts)
        }
    }

    private fun connectWithRetry(deviceKey: String, maxAttempts: Int?) {
        disconnect()

        connectJob = viewModelScope.launch {
            var attempt = 0

            while ((maxAttempts == null || attempt < maxAttempts) && isActive) {
                attempt++

                // if the connection is already connected, update the UI
                if (actionCableService.isConnected()) {
                    _uiState.update {
                        it.copy(connectionPhase = ConnectionPhase.Connected)
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.Connecting(attempt),
                    )
                }

                try {
                    actionCableService.connect(deviceKey)

                    // Wait for connection state to change
                    var waitTime = 0L
                    val maxWaitTime = reconnectMaxDelayMs
                    while (waitTime < maxWaitTime && isActive) {
                        val currentState = actionCableService.connectionState.value
                        if (currentState == ActionCableService.ConnectionState.CONNECTED) {
                            _uiState.update {
                                it.copy(connectionPhase = ConnectionPhase.Connected)
                            }
                            return@launch
                        }
                        if (currentState == ActionCableService.ConnectionState.ERROR) {
                            break
                        }

                        // Wait for a bit before pulling the connectionState again
                        delay(500)
                        waitTime += 500
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Connection attempt $attempt failed", error)
                }

                // Infinite retry or hasn't reached max attempts
                if (maxAttempts == null || attempt < maxAttempts) {
                    val delayMillis = (reconnectBaseDelayMs * attempt).coerceAtMost(reconnectMaxDelayMs)
                    delay(delayMillis)
                }
            }

            // All attempts failed
            if (isActive && maxAttempts != null) {
                _uiState.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.Failed,
                    )
                }
            }
        }
    }

    private fun disconnect() {
        stopHeartbeat()
        stopConnectJob()
        actionCableService.disconnect()
    }

    private fun observeStoredDeviceKey() {
        viewModelScope.launch {
            settingsDataStore.deviceKey.collect { storedKey ->
                _uiState.update { current ->
                    val shouldSyncInput = storedKey != current.deviceKey
                    current.copy(
                        deviceKey = storedKey,
                        deviceKeyInput = if (shouldSyncInput) storedKey.orEmpty() else current.deviceKeyInput
                    )
                }

                // Auto-connect on app start if we have a stored key and not already connected/connecting
                if (!storedKey.isNullOrBlank() &&
                    _uiState.value.isConnectionIdle &&
                    actionCableService.connectionState.value == ActionCableService.ConnectionState.DISCONNECTED
                ) {
                    Log.d(TAG, "Auto-connecting with stored device key")
                    manualDisconnect.set(false)
                    _uiState.update {
                        it.copy(
                            connectionPhase = ConnectionPhase.Connecting(0),
                        )
                    }
                    connectWithRetry(storedKey, maxRetryAttempts)
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
                        val hasDeviceKey = !_uiState.value.deviceKey.isNullOrBlank()
                        if (hasDeviceKey) {
                            _uiState.update {
                                it.copy(
                                    connectionPhase = ConnectionPhase.Connected,
                                )
                            }
                            stopHeartbeat()
                            startHeartbeat()
                        }
                    }

                    ActionCableService.ConnectionState.DISCONNECTED,
                    ActionCableService.ConnectionState.ERROR -> {
                        stopHeartbeat()
                        if (manualDisconnect.get()) {
                            manualDisconnect.set(false)
                            return@collect
                        }

                        val isRetrying = connectJob?.isActive == true
                        val deviceKey = _uiState.value.deviceKey
                        if (!isRetrying && !deviceKey.isNullOrBlank()) {
                            _uiState.update {
                                it.copy(
                                    connectionPhase = ConnectionPhase.Connecting(0),
                                )
                            }
                            manualDisconnect.set(false)
                            connectWithRetry(deviceKey, null)
                        } else if (!isRetrying) {
                            _uiState.update {
                                it.copy(
                                    connectionPhase = ConnectionPhase.Idle,
                                )
                            }
                        }
                    }

                    ActionCableService.ConnectionState.CONNECTING -> {
                        // Connection state is managed by our retry logic
                    }
                }
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

    private fun stopConnectJob() {
        connectJob?.cancel()
        connectJob = null
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
