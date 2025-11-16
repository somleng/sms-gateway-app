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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.somleng.sms_gateway_app.R
import org.somleng.sms_gateway_app.ui.components.Footer
import org.somleng.sms_gateway_app.ui.components.PrimaryButton
import org.somleng.sms_gateway_app.ui.components.ToggleRow
import org.somleng.sms_gateway_app.ui.theme.SomlengTheme

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.ui.collectAsStateWithLifecycle()

    MainScreenContent(
        uiState = uiState,
        deviceKeyInput = uiState.deviceKeyInput,
        onDeviceKeyChange = viewModel::onDeviceKeyInputChange,
        onConnectClick = { key ->
            if (key.isNotBlank()) {
                viewModel.onConnectClick(key)
            }
        },
        onToggleReceiving = viewModel::toggleReceiving,
        onToggleSending = viewModel::toggleSending,
        onDisconnect = viewModel::onDisconnectClick,
        modifier = modifier
    )
}

@Composable
private fun MainScreenContent(
    uiState: MainUiState,
    deviceKeyInput: String,
    onDeviceKeyChange: (String) -> Unit,
    onConnectClick: (String) -> Unit,
    onToggleReceiving: (Boolean) -> Unit,
    onToggleSending: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    .padding(bottom = 16.dp)
            )

            // Unified connection status text
            ConnectionStatusText(uiState = uiState)

            Spacer(modifier = Modifier.height(24.dp))

            when {
                uiState.isConnected -> {
                    ConnectedContent(
                        uiState = uiState,
                        onToggleReceiving = onToggleReceiving,
                        onToggleSending = onToggleSending,
                        onDisconnect = onDisconnect
                    )
                }

                uiState.isConnecting -> {
                    ConnectingContent(
                        canDisconnect = uiState.canDisconnect,
                        onDisconnect = if (uiState.canDisconnect) onDisconnect else null
                    )
                }

                uiState.isConnectionFailed || uiState.isConnectionIdle -> {
                    DeviceKeyEntryContent(
                        deviceKeyInput = deviceKeyInput,
                        onDeviceKeyChange = onDeviceKeyChange,
                        onConnectClick = { onConnectClick(deviceKeyInput) },
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
private fun ConnectionStatusText(uiState: MainUiState) {
    val statusValue = when {
        uiState.isConnected -> R.string.connected
        uiState.isConnecting -> R.string.connecting
        uiState.isConnectionFailed -> R.string.connection_failed
        uiState.isConnectionIdle && uiState.deviceKey.isNullOrBlank() -> R.string.not_configured
        uiState.isConnectionIdle && !uiState.deviceKey.isNullOrEmpty() -> R.string.disconnected
        else -> R.string.disconnected
    }

    val statusText = stringResource(statusValue)

    val statusColor = when  {
        uiState.isConnected -> MaterialTheme.colorScheme.primary
        uiState.isConnectionFailed -> MaterialTheme.colorScheme.error
        uiState.isConnectionIdle && !uiState.deviceKey.isNullOrBlank() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = buildAnnotatedString {
            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                append("Connection: ")
            }
            withStyle(style = SpanStyle(color = statusColor)) {
                append(statusText)
            }
        },
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
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
private fun ConnectingContent(
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

        if (canDisconnect && onDisconnect != null) {
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(
                text = stringResource(R.string.cancel),
                onClick = onDisconnect,
                isDanger = true
            )
        }
    }
}

@Composable
private fun DeviceKeyEntryContent(
    deviceKeyInput: String,
    onDeviceKeyChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    isConnecting: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = deviceKeyInput,
            onValueChange = onDeviceKeyChange,
            label = { Text(stringResource(R.string.device_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        PrimaryButton(
            text = stringResource(R.string.connect),
            onClick = onConnectClick,
            enabled = deviceKeyInput.isNotBlank(),
            isLoading = isConnecting
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenConnectedPreview() {
    SomlengTheme {
        MainScreenContent(
            uiState = MainUiState(
                connectionPhase = ConnectionPhase.Connected,
                receivingEnabled = true,
                sendingEnabled = false,
                deviceKey = "device-key-123",
                deviceKeyInput = "device-key-123"
            ),
            deviceKeyInput = "device-key-123",
            onDeviceKeyChange = {},
            onConnectClick = { _ -> },
            onToggleReceiving = {},
            onToggleSending = {},
            onDisconnect = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenConnectingPreview() {
    SomlengTheme {
        MainScreenContent(
            uiState = MainUiState(
                connectionPhase = ConnectionPhase.Connecting(1),
                deviceKey = "device-key-123",
                deviceKeyInput = "device-key-123"
            ),
            deviceKeyInput = "device-key-123",
            onDeviceKeyChange = {},
            onConnectClick = { _ -> },
            onToggleReceiving = {},
            onToggleSending = {},
            onDisconnect = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenDeviceKeyPreview() {
    SomlengTheme {
        MainScreenContent(
            uiState = MainUiState(
                connectionPhase = ConnectionPhase.Idle,
                deviceKey = null,
                deviceKeyInput = ""
            ),
            deviceKeyInput = "",
            onDeviceKeyChange = {},
            onConnectClick = { _ -> },
            onToggleReceiving = {},
            onToggleSending = {},
            onDisconnect = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
