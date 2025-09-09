package org.somleng.sms_gateway_app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IncomingMessagingService : FirebaseMessagingService() {

    private val TAG = "IncomingMessagingService"
    // Define a request code for SMS permission (can be any integer)
    private val SMS_PERMISSION_REQUEST_CODE = 101

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "From: ${message.from}")

        // Check if message contains a data payload.
        message.data.isNotEmpty().let {
            Log.d(TAG, "Message data payload: " + message.data)

            val phoneNumber = message.data["phoneNumber"]
            val messageBody = message.data["messageBody"]

            if (phoneNumber != null && messageBody != null) {
                sendSms(phoneNumber, messageBody)
            } else {
                Log.d(TAG, "Phone number or message body is missing in the data payload.")
            }
        }
    }

    private fun sendSms(phoneNumber: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val smsManager: SmsManager = this.getSystemService(SmsManager::class.java)

                // For long messages, divide the message into parts
                val parts = smsManager.divideMessage(message)
                val numParts = parts.size

                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()

                for (i in 0 until numParts) {
                    val sentPI = PendingIntent.getBroadcast(this, 0, Intent("SMS_SENT"), PendingIntent.FLAG_IMMUTABLE)
                    val deliveredPI = PendingIntent.getBroadcast(this, 0, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE)
                    sentIntents.add(sentPI)
                    deliveredIntents.add(deliveredPI)
                }

                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
                Log.d(TAG, "SMS sent successfully to $phoneNumber: $message")
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed to send: ${e.message}")
                e.printStackTrace()
            }
        } else {
            // Permission is not granted.
            // You might want to log this or handle it differently.
            // For a service, requesting permission directly is tricky.
            // It's best to ensure permission is granted before this service is active
            // or have a fallback mechanism.
            Log.e(TAG, "SEND_SMS permission not granted.")
            // Consider sending a broadcast to an Activity to request permission
            // Or notify the user through a different channel that SMS could not be sent.
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")
        // TODO: send token to your server
    }
}
