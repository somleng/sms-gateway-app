package org.somleng.sms_gateway_app.services

import com.google.gson.JsonObject
import com.hosopy.actioncable.Subscription

/**
 * Interface for reporting SMS delivery status.
 */
interface DeliveryStatusSink {
    fun reportStatus(messageId: String?, status: String)
}

/**
 * Default implementation that reports status via ActionCable subscription.
 */
class ActionCableDeliveryStatusSink(
    private val getSubscription: () -> Subscription?
) : DeliveryStatusSink {
    override fun reportStatus(messageId: String?, status: String) {
        if (messageId == null) return

        val payload = JsonObject().apply {
            addProperty("id", messageId)
            addProperty("status", status)
        }

        runCatching {
            getSubscription()?.perform("sent", payload)
        }
    }
}

