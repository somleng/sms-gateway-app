package org.somleng.sms_gateway_app.feature.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.somleng.sms_gateway_app.R
import org.somleng.sms_gateway_app.ui.components.Footer
import org.somleng.sms_gateway_app.ui.components.PrimaryButton
import org.somleng.sms_gateway_app.ui.components.ToggleRow

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.ui.collectAsStateWithLifecycle()
    var deviceKeyInput by rememberSaveable { mutableStateOf(uiState.deviceKey.orEmpty()) }

    LaunchedEffect(uiState.deviceKey) {
        if (!uiState.isAutoConnecting && !uiState.isReconnecting) {
            deviceKeyInput = uiState.deviceKey.orEmpty()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Image(
                painter = painterResource(id = R.drawable.somleng_logo),
                contentDescription = stringResource(R.string.somleng_logo),
                modifier = Modifier
                    .size(150.dp)
                    .padding(bottom = 32.dp)
            )

            when {
                uiState.isConnected -> {
                    ConnectedContent(
                        uiState = uiState,
                        onToggleReceiving = viewModel::toggleReceiving,
                        onToggleSending = viewModel::toggleSending,
                        onDisconnect = viewModel::onDisconnectClick
                    )
                }
                uiState.isAutoConnecting || uiState.isReconnecting -> {
                    AutoConnectingContent(
                        statusText = uiState.connectionStatusText,
                        canDisconnect = uiState.canDisconnect,
                        onDisconnect = if (uiState.canDisconnect) viewModel::onDisconnectClick else null
                    )
                }
                else -> {
                    DeviceKeyEntryContent(
                        deviceKey = deviceKeyInput,
                        onDeviceKeyChange = { deviceKeyInput = it },
                        onConnectClick = {
                            if (deviceKeyInput.isNotBlank()) {
                                viewModel.onConnectClick(deviceKeyInput)
                            }
                        },
                        statusText = uiState.connectionStatusText,
                        isConnecting = uiState.isConnecting
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Footer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun ConnectedContent(
    uiState: MainUiState,
    onToggleReceiving: (Boolean) -> Unit,
    onToggleSending: (Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${stringResource(R.string.status)}: ${uiState.connectionStatusText}",
            style = MaterialTheme.typography.titleMedium,
            color = if (uiState.connectionStatusText.contains("Online", ignoreCase = true)) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(bottom = 24.dp)
        )

        ToggleRow(
            text = stringResource(R.string.inbound_message),
            checked = uiState.receivingEnabled,
            onCheckedChange = onToggleReceiving,
            contentDescription = stringResource(R.string.toggle_receiving_messages)
        )

        ToggleRow(
            text = stringResource(R.string.outbound_message),
            checked = uiState.sendingEnabled,
            onCheckedChange = onToggleSending,
            contentDescription = stringResource(R.string.toggle_sending_messages)
        )

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = stringResource(R.string.disconnect),
            onClick = onDisconnect,
            isDanger = true
        )
    }
}

@Composable
private fun AutoConnectingContent(
    statusText: String,
    canDisconnect: Boolean,
    onDisconnect: (() -> Unit)?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )

        if (canDisconnect && onDisconnect != null) {
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(
                text = stringResource(R.string.disconnect),
                onClick = onDisconnect,
                isDanger = true
            )
        }
    }
}

@Composable
private fun DeviceKeyEntryContent(
    deviceKey: String,
    onDeviceKeyChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    statusText: String,
    isConnecting: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = deviceKey,
            onValueChange = onDeviceKeyChange,
            label = { Text(stringResource(R.string.enter_device_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        PrimaryButton(
            text = stringResource(R.string.connect),
            onClick = onConnectClick,
            enabled = deviceKey.isNotBlank(),
            isLoading = isConnecting
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (statusText.contains("error", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

