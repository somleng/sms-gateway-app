package org.somleng.sms_gateway_app.services

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager
import androidx.core.content.getSystemService

/**
 * Interface for dispatching SMS messages.
 */
interface SmsDispatcher {
    fun sendTextMessage(
        destinationAddress: String,
        scAddress: String?,
        text: String,
        sentIntent: PendingIntent?,
        deliveryIntent: PendingIntent?
    )
}

/**
 * Default implementation using Android's SmsManager.
 */
class AndroidSmsDispatcher(private val context: Context) : SmsDispatcher {
    private val smsManager: SmsManager by lazy {
        context.getSystemService(SmsManager::class.java)
    }

    override fun sendTextMessage(
        destinationAddress: String,
        scAddress: String?,
        text: String,
        sentIntent: PendingIntent?,
        deliveryIntent: PendingIntent?
    ) {
        smsManager.sendTextMessage(
            destinationAddress,
            scAddress,
            text,
            sentIntent,
            deliveryIntent
        )
    }
}

