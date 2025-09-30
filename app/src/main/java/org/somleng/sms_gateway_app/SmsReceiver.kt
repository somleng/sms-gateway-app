package org.somleng.sms_gateway_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.services.ActionCableService

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"
    // Using Dispatchers.IO for network operations that might be triggered by ActionCableService
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d(TAG, "SMS_RECEIVED_ACTION intent received.")
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            messages?.forEach { smsMessage ->
                val from = smsMessage.originatingAddress
                val body = smsMessage.messageBody

                // The 'to' address is this device's phone number which received the SMS.
                // Programmatically obtaining it can be complex.
                // Using a placeholder. The server identifies the device via X-Device-Key.
                // If your server specifically needs the MSISDN of the receiving SIM for the 'to' field,
                // this placeholder should be replaced with the actual number.
                val devicePhoneNumberPlaceholder = "this_device_number"

                if (from != null && body != null) {
                    Log.i(TAG, "Received SMS: From='$from', To (placeholder)='${devicePhoneNumberPlaceholder}', Body has ${body.length} chars.")

                    coroutineScope.launch {
                        try {
                            val appContext = context.applicationContext
                            val actionCableService = ActionCableService(appContext)

                            // Forward the SMS.
                            // Note: For this to work, ActionCableService must be connected to the server.
                            // The forwardSmsToServer method internally checks if messageSubscription is available.
                            // Consider a more robust way to manage ActionCableService's lifecycle and connection state,
                            // e.g., using a singleton or an Android Service that the app keeps connected.
                            actionCableService.forwardSmsToServer(from, devicePhoneNumberPlaceholder, body)
                            Log.d(TAG, "Attempted to forward SMS from $from to ActionCableService.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in coroutine when forwarding SMS from $from: ${e.message}", e)
                        }
                    }
                } else {
                    Log.w(TAG, "SMS data incomplete: from=$from or body is null. Not forwarding.")
                }
            }
        }
    }
}
