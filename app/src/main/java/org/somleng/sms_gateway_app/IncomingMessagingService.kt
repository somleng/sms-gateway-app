package org.somleng.sms_gateway_app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IncomingMessagingService : FirebaseMessagingService() {

    private val TAG = "IncomingMessagingService"

    override fun onMessageReceived(message: RemoteMessage) {
        // If you use data payloads only:
        val data = message.data
        Log.d(TAG, "onMessageReceived data=$data")

        // If your backend sends notification payloads:
        val title = message.notification?.title
        val body = message.notification?.body
        Log.d(TAG, "notification: $title - $body")
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")
        // TODO: send token to your server
    }
}
