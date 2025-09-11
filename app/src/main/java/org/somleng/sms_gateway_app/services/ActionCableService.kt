package org.somleng.sms_gateway_app.services

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hosopy.actioncable.ActionCable
import com.hosopy.actioncable.Channel
import com.hosopy.actioncable.Consumer
import com.hosopy.actioncable.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.HashMap

class ActionCableService(private val context: Context) {
    private val TAG = "ActionCableService"

    // Connection state management
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var consumer: Consumer? = null
    private var connectionSubscription: Subscription? = null
    private var messageSubscription: Subscription? = null
    private var deviceToken: String? = null

    // SMS permissions check
    private val smsManager = SmsManager.getDefault()

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    /**
     * Connect to the Somleng ActionCable server using device token
     */
    suspend fun connect(deviceToken: String) {
        try {
            Log.d(TAG, "Connecting to ActionCable with device token")
            this.deviceToken = deviceToken
            _connectionState.value = ConnectionState.CONNECTING

            // Create WebSocket URI for Somleng server
            val uri = URI("wss://app.somleng.org/cable")
            Log.d(TAG, "Connecting to WebSocket URI: $uri")

            // Configure consumer options
            val options = Consumer.Options().apply {
                reconnection = true
                reconnectionMaxAttempts = 5

                // Authentication using X-Device-Key header
                val headers = HashMap<String, String>()
                headers["X-Device-Key"] = deviceToken
                headers["User-Agent"] = "SomlengSMSGatewayApp/1.0"
                this.headers = headers

                Log.d(TAG, "ActionCable headers configured")
            }

            // Create consumer
            consumer = ActionCable.createConsumer(uri, options)

            // Create channel subscription
            val connectionChannel = Channel("SMSGatewayConnectionChannel")
            Log.d(TAG, "Creating subscription to channel: SMSGatewayConnectionChannel")

            // Create subscription with callbacks
            connectionSubscription = consumer?.subscriptions?.create(connectionChannel)?.apply {
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

                onFailed { exception ->
                    Log.e(TAG, "ActionCable connection failed", exception)
                    _connectionState.value = ConnectionState.ERROR
                }
            }

            val messageChannel = Channel("SMSMessageChannel")
            Log.d(TAG, "Creating subscription to channel: SMSMessageChannel")

            // Create subscription with callbacks
            messageSubscription = consumer?.subscriptions?.create(messageChannel)?.apply {
                onReceived { data ->
                    Log.d(TAG, "Received message from server: $data")
                    handleIncomingMessage(data)
                }
            }


            // Establish connection
            Log.d(TAG, "Connecting to ActionCable...")
            consumer?.connect()

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to ActionCable", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Disconnect from ActionCable server
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from ActionCable")
        connectionSubscription = null
        messageSubscription = null
        consumer?.disconnect()
        consumer = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Handle incoming messages from ActionCable
     */
    private fun handleIncomingMessage(data: JsonElement) {
        try {
            val jsonObject = data.asJsonObject

            // Extract message details
            val messageId = jsonObject.get("id")?.asString
            val channel = jsonObject.get("channel").safeAsString()
            val fromNumber = jsonObject.get("from")?.asString
            val phoneNumber = jsonObject.get("to")?.asString
            val messageBody = jsonObject.get("body")?.asString

            Log.d(TAG, "MessageId: $messageId, Channel: $channel, From: $fromNumber, To: $phoneNumber, Body: $messageBody")

            if (phoneNumber != null && messageBody != null) {
                sendSms(phoneNumber, messageBody, messageId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message", e)
        }
    }

    /**
     * Send SMS message
     */
    private fun sendSms(phoneNumber: String, messageBody: String, messageId: String?) {
        try {
            Log.d(TAG, "Sending SMS to $phoneNumber: $messageBody")

            // Send the SMS
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                messageBody,
                null, // sentIntent - could add delivery confirmation
                null  // deliveryIntent - could add delivery confirmation
            )

            Log.d(TAG, "SMS sent successfully to $phoneNumber")

            // Send delivery status back to server
            sendDeliveryStatus(messageId, "sent", "SMS sent successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS to $phoneNumber", e)
            sendDeliveryStatus(messageId, "failed", e.message ?: "Unknown error")
        }
    }

    /**
     * Send delivery status back to server
     */
    private fun sendDeliveryStatus(messageId: String?, status: String, message: String) {
        try {
            if (messageId == null) return

            val statusData = JsonObject().apply {
                addProperty("id", messageId)
                addProperty("status", status)
            }

            messageSubscription?.perform("sent", statusData)
            Log.d(TAG, "Sent delivery status: $status for message $messageId")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending delivery status", e)
        }
    }

    /**
     * Send heartbeat to server (can be called periodically)
     */
    fun sendHeartbeat() {
        try {
            connectionSubscription?.perform("ping")
            Log.d(TAG, "Sent heartbeat")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending heartbeat", e)
        }
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED && consumer != null
    }

    /**
     * Get current connection status
     */
    fun getConnectionStatus(): String {
        return when (_connectionState.value) {
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.ERROR -> "Error"
        }
    }

    /**
     * Safely extract string value from JsonElement, handling null values
     */
    private fun JsonElement?.safeAsString(): String? {
        return if (this == null || this.isJsonNull) null else this.asString
    }
}
