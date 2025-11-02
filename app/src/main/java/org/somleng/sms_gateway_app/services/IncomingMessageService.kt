package org.somleng.sms_gateway_app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

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

                coroutineScope.launch {
                    try {
                        val appContext = context.applicationContext
                        val settingsDataStore = SettingsDataStore(appContext)

                        if (!settingsDataStore.isReceivingEnabled()) {
                            Log.i(TAG, "Inbound Message is disabled.")
                            return@launch
                        }

                        val phoneNumber = settingsDataStore
                            .getPhoneNumber()
                            ?.takeUnless { it.isBlank() }

                        if (phoneNumber == null) {
                            Log.w(
                                TAG,
                                "No configured phone number found."
                            )

                            return@launch
                        }

                        Log.i(TAG, "Received SMS: From='$from', To='${phoneNumber}'")

                        val actionCableService = ActionCableService.getInstance(appContext)
                        actionCableService.notifyNewInboundMessage(from, phoneNumber, body)
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
