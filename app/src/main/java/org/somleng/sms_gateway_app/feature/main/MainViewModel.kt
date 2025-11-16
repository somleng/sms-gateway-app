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
    private val heartbeatIntervalMs = 30_000L
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
        cancelConnect()
        actionCableService.disconnect()
    }

    fun onConnectClick(deviceKey: String) {
        if (_uiState.value.isConnecting) return
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

    private fun connect(deviceKey: String) {
        manualDisconnect.set(false)
        val trimmedKey = deviceKey.trim()
        if (trimmedKey.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Device key is required") }
            return
        }

        viewModelScope.launch {
            settingsDataStore.setDeviceKey(trimmedKey)
            _uiState.update {
                it.copy(
                    deviceKey = trimmedKey,
                    connectionPhase = ConnectionPhase.Connecting(0),
                    statusMessage = "Connecting"
                )
            }
            connectWithRetry(trimmedKey, maxRetryAttempts)
        }
    }

    private fun disconnect() {
        manualDisconnect.set(true)
        viewModelScope.launch {
            cancelConnect()
            actionCableService.disconnect()

            _uiState.update {
                it.copy(
                    connectionPhase = ConnectionPhase.Idle,
                    statusMessage = "Disconnected"
                )
            }
        }
    }

    private fun connectWithRetry(deviceKey: String, maxAttempts: Int?) {
        cancelConnect()

        connectJob = viewModelScope.launch {
            var attempt = 0

            while ((maxAttempts == null || attempt < maxAttempts) && isActive) {
                attempt++

                _uiState.update {
                    it.copy(
                        connectionPhase = ConnectionPhase.Connecting(attempt),
                        statusMessage = "Connecting"
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
                            return@launch
                        }
                        if (currentState == ActionCableService.ConnectionState.ERROR) {
                            break
                        }
                        delay(500)
                        waitTime += 500
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Connection attempt $attempt failed", error)
                }

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
                        statusMessage = "Failed"
                    )
                }
            }
        }
    }

    private fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
    }

    private fun observeStoredDeviceKey() {
        viewModelScope.launch {
            settingsDataStore.deviceKey.collect { storedKey ->
                val currentPhase = _uiState.value.connectionPhase

                _uiState.update { current ->
                    current.copy(deviceKey = storedKey)
                }

                // Auto-connect on app start if we have a stored key and not already connected/connecting
                if (storedKey != null &&
                    currentPhase is ConnectionPhase.Idle &&
                    actionCableService.connectionState.value == ActionCableService.ConnectionState.DISCONNECTED
                ) {
                    Log.d(TAG, "Auto-connecting with stored device key")
                    manualDisconnect.set(false)
                    _uiState.update {
                        it.copy(
                            connectionPhase = ConnectionPhase.Connecting(0),
                            statusMessage = "Connecting"
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
                                    statusMessage = "Online"
                                )
                            }
                            cancelConnect()
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
                                    statusMessage = "Connecting"
                                )
                            }
                            manualDisconnect.set(false)
                            connectWithRetry(deviceKey, null)
                        } else if (!isRetrying) {
                            _uiState.update {
                                it.copy(
                                    connectionPhase = ConnectionPhase.Idle,
                                    statusMessage = if (state == ActionCableService.ConnectionState.ERROR) {
                                        "Failed"
                                    } else {
                                        "Disconnected"
                                    }
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

    companion object {
        private const val TAG = "MainViewModel"
    }
}
