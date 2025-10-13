package org.somleng.sms_gateway_app.services

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.getSystemService
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hosopy.actioncable.ActionCable
import com.hosopy.actioncable.Channel
import com.hosopy.actioncable.Consumer
import com.hosopy.actioncable.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.net.URI
import org.somleng.sms_gateway_app.data.preferences.AppSettingsDataStore

class ActionCableService(private val context: Context) {

    private val smsManager: SmsManager by lazy {
        context.getSystemService<SmsManager>() ?: SmsManager.getDefault()
    }

    private val appSettingsDataStore = AppSettingsDataStore(context)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var consumer: Consumer? = null
    private var connectionSubscription: Subscription? = null
    private var messageSubscription: Subscription? = null

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    suspend fun connect(deviceKey: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { deviceToken ->
            if (deviceToken.isNullOrBlank()) {
                Log.w(TAG, "Device token is missing")
                _connectionState.value = ConnectionState.ERROR
                return@addOnSuccessListener
            }

            runCatching {
                tearDownConnection()
                _connectionState.value = ConnectionState.CONNECTING

                val consumer = createConsumer(deviceKey, deviceToken).also { this.consumer = it }
                connectionSubscription = subscribeToConnectionEvents(consumer)
                messageSubscription = subscribeToMessageEvents(consumer)

                Log.d(TAG, "Connecting to ActionCable with device token: $deviceToken")
                consumer.connect()
            }.onFailure { error ->
                Log.e(TAG, "Error connecting to ActionCable", error)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from ActionCable")
        tearDownConnection()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendHeartbeat() {
        runCatching {
            connectionSubscription?.perform("ping")
            Log.d(TAG, "Sent heartbeat")
        }.onFailure { error ->
            Log.e(TAG, "Error sending heartbeat", error)
        }
    }

    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }

    fun forwardSmsToServer(from: String, to: String, body: String) {
        if (messageSubscription == null) {
            Log.w(TAG, "Cannot forward SMS; not connected to ActionCable")
            return
        }

        if (!runBlocking { appSettingsDataStore.isReceivingEnabled() }) {
            Log.i(TAG, "Receiving disabled; skipping forwarding SMS from $from to Somleng.")
            return
        }

        val payload = JsonObject().apply {
            addProperty("from", from)
            addProperty("to", to)
            addProperty("body", body)
        }

        runCatching {
            messageSubscription?.perform("received", payload)
            Log.d(TAG, "Forwarded SMS to server (from=$from)")
        }.onFailure { error ->
            Log.e(TAG, "Error forwarding SMS to server", error)
        }
    }

    fun sendMessage(messageId: String?) {
        val data = JsonObject().apply {
            addProperty("id", messageId)
        }

        messageSubscription?.perform("message_send_requested", data)
    }

    private fun createConsumer(deviceKey: String, deviceToken: String): Consumer {
        val options = Consumer.Options().apply {
            reconnection = true
            reconnectionMaxAttempts = 5
            headers = mapOf(
                HEADER_DEVICE_KEY to deviceKey,
                HEADER_DEVICE_TOKEN to deviceToken,
                HEADER_USER_AGENT to USER_AGENT
            )
        }

        return ActionCable.createConsumer(SOMLENG_URI, options)
    }

    private fun subscribeToConnectionEvents(consumer: Consumer): Subscription {
        val channel = Channel(CONNECTION_CHANNEL)

        return consumer.subscriptions.create(channel).apply {
            onConnected {
                Log.d(TAG, "ActionCable connected successfully")
                _connectionState.value = ConnectionState.CONNECTED
            }

            onRejected {
                Log.e(TAG, "ActionCable subscription rejected")
                _connectionState.value = ConnectionState.ERROR
            }

            onDisconnected {
                Log.d(TAG, "ActionCable disconnected")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            onFailed { error ->
                Log.e(TAG, "ActionCable connection failed", error)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    private fun subscribeToMessageEvents(consumer: Consumer): Subscription {
        val channel = Channel(MESSAGE_CHANNEL)

        return consumer.subscriptions.create(channel).apply {
            onReceived { data ->
                val payload = runCatching { data.asJsonObject }.getOrNull()
                if (payload == null) {
                    Log.w(TAG, "Ignoring message; payload is not JSON object: $data")
                    return@onReceived
                }

                val messgeType = payload["type"].safeAsString()
                if (messgeType == "message_send_request") {
                    val messageId = payload["message_id"].safeAsString()

                    Log.d(TAG, "Received message from server: $data")
                    sendMessage(messageId)
                } else if (messgeType == "message_send_request_confirmed") {
                    Log.d(TAG, "Received message from server: $data")
                    handleIncomingMessage(data)
                }
            }
        }
    }

    private fun handleIncomingMessage(data: JsonElement) {
        val payload = runCatching { data.asJsonObject["message"].asJsonObject }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Ignoring message; payload is not message data: $data")
            return
        }

        val messageId = payload["id"].safeAsString()
        val phoneNumber = payload["to"].safeAsString()
        val body = payload["body"].safeAsString()

        if (phoneNumber.isNullOrBlank() || body.isNullOrBlank()) {
            Log.w(TAG, "Missing SMS details in payload: $payload")
            return
        }

        if (!runBlocking { appSettingsDataStore.isSendingEnabled() }) {
            Log.i(TAG, "Sending disabled; reporting failure for message ${messageId ?: "<no-id>"} without dispatching SMS.")
            sendDeliveryStatus(messageId, DELIVERY_STATUS_FAILED, SENDING_DISABLED_MESSAGE)
            return
        }

        sendSms(phoneNumber, body, messageId)
    }

    private fun sendSms(phoneNumber: String, messageBody: String, messageId: String?) {
        runCatching {
            smsManager.sendTextMessage(phoneNumber, null, messageBody, null, null)
            Log.d(TAG, "SMS sent to $phoneNumber")
            sendDeliveryStatus(messageId, DELIVERY_STATUS_SENT)
        }.onFailure { error ->
            Log.e(TAG, "Error sending SMS to $phoneNumber", error)
            sendDeliveryStatus(messageId, DELIVERY_STATUS_FAILED, error.message ?: "Unknown error")
        }
    }

    private fun sendDeliveryStatus(messageId: String?, status: String, message: String? = null) {
        if (messageId == null) return

        val payload = JsonObject().apply {
            addProperty("id", messageId)
            addProperty("status", status)
            message?.let { addProperty("error", it) }
        }

        runCatching {
            messageSubscription?.perform("sent", payload)
            Log.d(TAG, "Reported delivery status '$status' for message $messageId")
        }.onFailure { error ->
            Log.e(TAG, "Error sending delivery status", error)
        }
    }

    private fun tearDownConnection() {
        connectionSubscription = null
        messageSubscription = null
        consumer?.disconnect()
        consumer = null
    }

    private fun JsonElement?.safeAsString(): String? {
        return if (this == null || this.isJsonNull) null else runCatching { this.asString }.getOrNull()
    }

    companion object {
        private const val TAG = "ActionCableService"
        private const val USER_AGENT = "SomlengSMSGatewayApp/1.0"
        private const val CONNECTION_CHANNEL = "SMSGatewayConnectionChannel"
        private const val MESSAGE_CHANNEL = "SMSMessageChannel"
        private const val HEADER_DEVICE_KEY = "X-Device-Key"
        private const val HEADER_DEVICE_TOKEN = "X-Device-Token"
        private const val HEADER_USER_AGENT = "User-Agent"
        // Emulator ↔ host (10.0.2.2)
        private val SOMLENG_URI: URI = URI("ws://10.0.2.2:8080/cable")
//        private val SOMLENG_URI: URI = URI("wss://app.somleng.org/cable")
        private const val DELIVERY_STATUS_SENT = "sent"
        private const val DELIVERY_STATUS_FAILED = "failed"
        private const val SENDING_DISABLED_MESSAGE = "Sending disabled by user"

        private var instance: ActionCableService? = null

        @Synchronized
        fun getInstance(context: Context): ActionCableService {
            if (instance == null) {
                instance = ActionCableService(context.applicationContext)
            }
            return instance!!
        }
    }
}
