package org.somleng.sms_gateway_app.feature.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore
import org.somleng.sms_gateway_app.services.ActionCableService
import org.somleng.sms_gateway_app.viewmodels.ConnectionViewModel

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Use existing ConnectionViewModel internally to preserve business logic
    private val connectionViewModel = ConnectionViewModel(application)
    
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    init {
        // Observe connection state
        viewModelScope.launch {
            connectionViewModel.uiState.collect { connectionState ->
                _ui.update {
                    MainUiState(
                        isConnected = connectionState.isConnected,
                        receivingEnabled = connectionState.isReceivingEnabled,
                        sendingEnabled = connectionState.isSendingEnabled,
                        busy = connectionState.isConnecting || connectionState.isAutoConnecting || connectionState.isReconnecting,
                        connectionStatusText = connectionState.connectionStatusText,
                        deviceKey = connectionState.deviceKey,
                        isAutoConnecting = connectionState.isAutoConnecting,
                        isReconnecting = connectionState.isReconnecting
                    )
                }
            }
        }
    }

    fun toggleReceiving(enabled: Boolean) {
        connectionViewModel.toggleReceiving(enabled)
    }

    fun toggleSending(enabled: Boolean) {
        connectionViewModel.toggleSending(enabled)
    }

    fun onConnectClick(deviceKey: String) {
        if (_ui.value.busy) return
        connectionViewModel.connect(deviceKey)
    }

    fun onDisconnectClick() {
        connectionViewModel.disconnect()
    }

    fun onAppForegrounded() {
        connectionViewModel.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        connectionViewModel.onAppBackgrounded()
    }
}

