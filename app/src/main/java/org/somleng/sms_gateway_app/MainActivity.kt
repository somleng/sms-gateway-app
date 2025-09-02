package org.somleng.sms_gateway_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.ui.theme.SMSGatewayAppTheme
import androidx.compose.material3.ButtonDefaults
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMSGatewayAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SMSGatewayScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SMSGatewayScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val appSettingsDataStore = remember { AppSettingsDataStore(context) }

    // Read the device key from DataStore
    val storedDeviceKeyFlow = appSettingsDataStore.deviceKeyFlow
    val storedDeviceKey by storedDeviceKeyFlow.collectAsState(initial = null)

    // Local state for the text field
    var deviceKeyInput by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var isReceivingEnabled by remember { mutableStateOf(true) }
    var isSendingEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(storedDeviceKey) {
        if (storedDeviceKey != null) {
            deviceKeyInput = storedDeviceKey!!
            if (deviceKeyInput.isNotBlank()) {
                isConnected = true
            }
        } else {
            deviceKeyInput = ""
            isConnected = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Image(
                painter = painterResource(id = R.drawable.somleng_logo),
                contentDescription = "Somleng Logo",
                modifier = Modifier
                    .size(150.dp)
                    .padding(bottom = 32.dp)
            )

            Text(
                text = "Somleng SMS Gateway",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (isConnected) {
                ConnectedScreen(
                    isReceivingEnabled = isReceivingEnabled,
                    onReceivingChange = { isReceivingEnabled = it },
                    isSendingEnabled = isSendingEnabled,
                    onSendingChange = { isSendingEnabled = it },
                    onDisconnectClick = {
                        coroutineScope.launch {
                            appSettingsDataStore.clearDeviceKey()
                        }
                    }
                )
            } else {
                DeviceKeyEntryScreen(
                    deviceKey = deviceKeyInput,
                    onDeviceKeyChange = { deviceKeyInput = it },
                    onConnectClick = {
                        if (deviceKeyInput.isNotBlank()) {
                            coroutineScope.launch {
                                appSettingsDataStore.saveDeviceKey(deviceKeyInput)
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Somleng Inc.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DeviceKeyEntryScreen(
    deviceKey: String,
    onDeviceKeyChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = deviceKey,
            onValueChange = onDeviceKeyChange,
            label = { Text("Enter Device Key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Enter the device key to connect to your SMS Gateway. You can find this device key on your Somleng dashboard.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ConnectedScreen(
    isReceivingEnabled: Boolean,
    onReceivingChange: (Boolean) -> Unit,
    isSendingEnabled: Boolean,
    onSendingChange: (Boolean) -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row {
            Text(
                "Status: ",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Text(
                "Connected",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF006400),
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        SwitchOptionRow(
            text = "Receiving Messages",
            checked = isReceivingEnabled,
            onCheckedChange = onReceivingChange
        )
        SwitchOptionRow(
            text = "Sending Messages",
            checked = isSendingEnabled,
            onCheckedChange = onSendingChange
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDisconnectClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = "Disconnect",
                color = MaterialTheme.colorScheme.onError
            )
        }
    }
}

@Composable
fun SwitchOptionRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true, name = "Disconnected State")
@Composable
fun GreetingPreview() {
    SMSGatewayAppTheme {
        Scaffold { innerPadding ->
            SMSGatewayScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}

@Preview(showBackground = true, name = "Connected State")
@Composable
fun ConnectedScreenPreview() {
    SMSGatewayAppTheme {
        Scaffold { innerPadding ->
            // For preview, we simulate the connected state directly
            // DataStore won't work in Preview environment easily
            var isReceivingEnabled by remember { mutableStateOf(true) }
            var isSendingEnabled by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Image(
                    painter = painterResource(id = R.drawable.somleng_logo),
                    contentDescription = "Somleng Logo",
                    modifier = Modifier
                        .size(150.dp)
                        .padding(bottom = 32.dp)
                )
                Text(
                    text = "Somleng SMS Gateway",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                ConnectedScreen(
                    isReceivingEnabled = isReceivingEnabled,
                    onReceivingChange = { isReceivingEnabled = it },
                    isSendingEnabled = isSendingEnabled,
                    onSendingChange = { isSendingEnabled = it },
                    onDisconnectClick = { /* Simulate disconnect */ }
                )
            }
        }
    }
}
