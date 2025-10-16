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
    override fun onReceive(context: Context, intent: Intent) {
        if (isNewSmsReceived(intent)) {
            Log.d(TAG, "SMS_RECEIVED_ACTION intent received.")

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            messages?.forEach { smsMessage ->
                val from = smsMessage.originatingAddress
                val body = smsMessage.messageBody

                if (from == null || body == null) {
                    Log.w(TAG, "Invalid incoming SMS: from=$from or body is null.")
                    return@forEach
                }

                // TODO: Figure out how to get the phone number from sim card
                val simPhoneNumber = "this_device_number"

                Log.i(TAG, "Received SMS: From='$from', To='${simPhoneNumber}'")

                coroutineScope.launch {
                    try {
                        val appContext = context.applicationContext
                        val appSettingsDataStore = AppSettingsDataStore(appContext)

                        if (!appSettingsDataStore.isReceivingEnabled()) {
                            Log.i(TAG, "Inbound Message is disabled.")
                            return@launch
                        }

                        val actionCableService = ActionCableService.getInstance(appContext)
                        actionCableService.notifyNewInboundMessage(from, simPhoneNumber, body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying new inbound message from $from: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun isNewSmsReceived(intent: Intent): Boolean {
        return intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION
    }

    companion object {
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private const val TAG = "IncomingMessageService"
    }
}
