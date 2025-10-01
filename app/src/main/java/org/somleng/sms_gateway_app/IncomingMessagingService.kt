package org.somleng.sms_gateway_app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class IncomingMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "Received FCM message: ${message.data}")

        val phoneNumber = message.data[PHONE_NUMBER_KEY]
        val messageBody = message.data[MESSAGE_BODY_KEY]

        if (phoneNumber.isNullOrBlank() || messageBody.isNullOrBlank()) {
            Log.w(TAG, "Missing phone number or message body; skipping SMS dispatch")
            return
        }

        if (!hasSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission not granted; unable to forward message")
            return
        }

        if (!runBlocking { AppSettingsDataStore(this@IncomingMessagingService).isSendingEnabled() }) {
            Log.i(TAG, "Sending disabled; ignoring FCM message destined for $phoneNumber")
            return
        }

        sendSms(phoneNumber, messageBody)
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendSms(phoneNumber: String, message: String) {
        val smsManager = getSystemService<SmsManager>() ?: SmsManager.getDefault()
        val parts = ArrayList(smsManager.divideMessage(message))
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveredIntents = ArrayList<PendingIntent>(parts.size)

        repeat(parts.size) {
            sentIntents += statusPendingIntent(SENT_ACTION)
            deliveredIntents += statusPendingIntent(DELIVERED_ACTION)
        }

        runCatching {
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveredIntents)
            Log.d(TAG, "SMS sent to $phoneNumber")
        }.onFailure { error ->
            Log.e(TAG, "Failed to send SMS to $phoneNumber", error)
        }
    }

    private fun statusPendingIntent(action: String): PendingIntent {
        val intent = Intent(action)
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        // TODO: Forward token to backend if required
    }

    companion object {
        private const val TAG = "IncomingMessagingService"
        private const val PHONE_NUMBER_KEY = "phoneNumber"
        private const val MESSAGE_BODY_KEY = "messageBody"
        private const val SENT_ACTION = "SMS_SENT"
        private const val DELIVERED_ACTION = "SMS_DELIVERED"
    }
}
