package org.somleng.sms_gateway_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Permission", "Notification permission granted")
                // FCM SDK (and your app) can post notifications.
            } else {
                Log.d("Permission", "Notification permission denied")
                // Inform user that notifications are disabled
                // You could show a dialog explaining why you need the permission
                // and how they can grant it in app settings.
            }
        }

    private var showSmsPermissionRationaleDialog by mutableStateOf(false)
    private var onSmsPermissionGranted: (() -> Unit)? = null
    private var onSmsPermissionDenied: (() -> Unit)? = null

    private val requestSmsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "SEND_SMS permission granted by user.")
                onSmsPermissionGranted?.invoke()
            } else {
                Log.d(TAG, "SEND_SMS permission denied by user.")
                // Optionally, explain to the user that the feature is unavailable
                // because the permission is needed. You could guide them to app settings.
                onSmsPermissionDenied?.invoke()
            }
            // Reset callbacks
            onSmsPermissionGranted = null
            onSmsPermissionDenied = null
        }

    fun ensureSmsPermission(
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null,
        showRationaleBeforeRequest: Boolean = true
    ) {
        this.onSmsPermissionGranted = onGranted
        this.onSmsPermissionDenied = onDenied

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "SEND_SMS permission already granted.")
                this.onSmsPermissionGranted?.invoke()
                this.onSmsPermissionGranted = null // Clear callback
                this.onSmsPermissionDenied = null  // Clear callback
            }
            showRationaleBeforeRequest && shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) -> {
                // We should show an explanation.
                // In a Compose app, you'd typically set a state variable
                // to trigger a Composable dialog.
                Log.d(TAG, "Showing rationale for SEND_SMS permission.")
                showSmsPermissionRationaleDialog = true
                // The actual request will be launched when the user interacts with the rationale dialog.
            }
            else -> {
                // No explanation needed; request the permission directly.
                Log.d(TAG, "Requesting SEND_SMS permission.")
                requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
        }
    }

    private fun requestSmsPermissionAfterRationale() {
        Log.d(TAG, "Requesting SEND_SMS permission after rationale.")
        requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
    }

    private fun askNotificationPermission() {
        // This is only necessary for API level 33 and higher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // FCM SDK (and your app) can post notifications.
                Log.d("Permission", "Notification permission already granted")
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // TODO: Display an educational UI explaining to the user why your app needs the
                // permission for a specific feature.
                // Then, trigger the permission request.
                // For now, just requesting directly:
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Directly ask for the permission.
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SMSGatewayAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SMSGatewayScreen(modifier = Modifier.padding(innerPadding))

                    if (showSmsPermissionRationaleDialog) {
                        SmsPermissionRationaleDialog(
                            onDismiss = {
                                showSmsPermissionRationaleDialog = false
                                // User dismissed rationale, you might still want to call onDenied
                                onSmsPermissionDenied?.invoke()
                                onSmsPermissionDenied = null
                                onSmsPermissionGranted = null
                            },
                            onConfirm = {
                                showSmsPermissionRationaleDialog = false
                                requestSmsPermissionAfterRationale() // Request after user confirms rationale
                            }
                        )
                    }
                }
            }
        }

        askNotificationPermission()

        ensureSmsPermission(
            onGranted = {
                Log.i(TAG, "SEND_SMS permission was granted on app start.")
                // You can perform any setup here that depends on SMS permission
            },
            onDenied = {
                Log.w(TAG, "SEND_SMS permission was denied on app start.")
                // Handle the case where the user denies permission at startup.
                // You might want to explain that certain features will be disabled
                // or guide them to settings if they change their mind.
            },
            // Set to true if you want to show your custom rationale dialog
            // if the system thinks a rationale should be shown.
            // Set to false if you want to directly show the system dialog
            // without your custom rationale first, even if shouldShowRequestPermissionRationale() is true.
            // For startup, `true` is generally better to explain why it's needed immediately.
            showRationaleBeforeRequest = true
        )

        // Get FCM Token (optional, for sending to your server)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            // Get new FCM registration token
            val token = task.result
            Log.d(TAG, "Current FCM Token: $token")
            // Send this token to your server if needed
            // myFirebaseMessagingService.sendRegistrationToServer(token) // or call a method in your ViewModel
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

@Composable
fun SmsPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SMS Permission Needed") },
        text = { Text("This app needs permission to send SMS messages to function as an SMS gateway. This allows it to forward messages received via push notifications as SMS.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

