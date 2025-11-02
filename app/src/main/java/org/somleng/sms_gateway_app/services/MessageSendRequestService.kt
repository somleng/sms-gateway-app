package org.somleng.sms_gateway_app.services

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

class MessageSendRequestService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "Received FCM message: ${message.data}")

        val messageType = message.data["type"]
        val messageId = message.data["message_id"]

        if (messageType != "message_send_request") {
            Log.w(TAG, "Ignore message type: $messageType")
            return
        }

        if (messageId.isNullOrBlank()) {
            Log.w(TAG, "Missing messageId")
            return
        }

        if (!hasSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission not granted")
            return
        }

        if (!runBlocking { SettingsDataStore(this@MessageSendRequestService).isSendingEnabled() }) {
            Log.i(TAG, "Sending disabled")
            return
        }

        ActionCableService.getInstance(this).sendMessageRequest(messageId)
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
    }

    companion object {
        private const val TAG = "MessageSendRequestService"
    }
}
