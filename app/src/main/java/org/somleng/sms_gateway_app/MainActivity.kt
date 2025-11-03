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
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.somleng.sms_gateway_app.feature.main.MainViewModel
import org.somleng.sms_gateway_app.feature.settings.SettingsViewModel
import org.somleng.sms_gateway_app.ui.theme.SomlengTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Permission", "Notification permission granted")
            } else {
                Log.d("Permission", "Notification permission denied")
                // NOTE: Inform user that notifications are disabled
            }
        }

    private var showSmsPermissionRationaleDialog by mutableStateOf(false)

    private val requestSmsPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val sendSmsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
            val receiveSmsGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false

            if (sendSmsGranted && receiveSmsGranted) {
                Log.d(TAG, "SEND_SMS and RECEIVE_SMS permissions granted by user.")
            } else {
                Log.d(TAG, "One or both SMS permissions were denied by user.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SomlengTheme {
                SomlengApp(
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel
                )

                if (showSmsPermissionRationaleDialog) {
                    SmsPermissionRationaleDialog(
                        onDismiss = {
                            showSmsPermissionRationaleDialog = false
                        },
                        onConfirm = {
                            showSmsPermissionRationaleDialog = false
                            requestSmsPermissionsAfterRationale()
                        }
                    )
                }
            }
        }

        askNotificationPermission()

        ensureSmsPermissions(
            onGranted = {
                Log.i(TAG, "SMS permissions were granted on app start.")
            },
            onDenied = {
                Log.w(TAG, "SMS permissions were denied on app start.")
            },
            showRationaleBeforeRequest = true
        )

        // Get FCM (Firebase) Token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            Log.d(TAG, "Current FCM Token: $token")
        }
    }

    fun ensureSmsPermissions(
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null,
        showRationaleBeforeRequest: Boolean = true
    ) {
        val permissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

        val allPermissionsGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        when {
            allPermissionsGranted -> {
                Log.d(TAG, "All SMS permissions already granted.")
            }
            showRationaleBeforeRequest && permissions.any { shouldShowRequestPermissionRationale(it) } -> {
                Log.d(TAG, "Showing rationale for SMS permissions.")
                showSmsPermissionRationaleDialog = true
            }
            else -> {
                Log.d(TAG, "Requesting SMS permissions.")
                requestSmsPermissionsLauncher.launch(permissions)
            }
        }
    }

    private fun askNotificationPermission() {
        // This is only necessary for API level 33 and higher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("Permission", "Notification permission already granted")
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // TODO: Display an educational UI explaining to the user why your app needs the
                // permission for a specific feature.
                // Then, trigger the permission request.
                // For now, just requesting directly:
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestSmsPermissionsAfterRationale() {
        Log.d(TAG, "Requesting SMS permissions after rationale.")
        requestSmsPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        )
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}


@Composable
fun SmsPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sms_permissions_needed)) },
        text = { Text(stringResource(R.string.sms_permissions_rationale)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
