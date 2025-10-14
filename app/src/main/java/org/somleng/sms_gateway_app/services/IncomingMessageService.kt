package org.somleng.sms_gateway_app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class IncomingMessageService : BroadcastReceiver() {
    private val TAG = "SmsReceiver"
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d(TAG, "SMS_RECEIVED_ACTION intent received.")
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            messages?.forEach { smsMessage ->
                val from = smsMessage.originatingAddress
                val body = smsMessage.messageBody

                // TODO: Figure out how to get the phone number from sim card
                val devicePhoneNumberPlaceholder = "this_device_number"

                if (from != null && body != null) {
                    Log.i(TAG, "Received SMS: From='$from', To (placeholder)='${devicePhoneNumberPlaceholder}', Body has ${body.length} chars.")

                    coroutineScope.launch {
                        try {
                            val appContext = context.applicationContext
                            val appSettingsDataStore = AppSettingsDataStore(appContext)

                            if (!appSettingsDataStore.isReceivingEnabled()) {
                                Log.i(TAG, "Receiving disabled; logging SMS from $from without forwarding to Somleng.")
                                return@launch
                            }

                            val actionCableService = ActionCableService.getInstance(appContext)

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
