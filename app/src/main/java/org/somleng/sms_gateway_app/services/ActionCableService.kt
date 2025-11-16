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
import java.net.URI
import kotlin.coroutines.resume
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.somleng.sms_gateway_app.BuildConfig
import org.somleng.sms_gateway_app.R
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore

class ActionCableService private constructor(private val context: Context) {

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val smsManager: SmsManager by lazy {
        context.getSystemService(SmsManager::class.java)
    }

    private val settingsDataStore = SettingsDataStore(context)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectMutex = Mutex()
    @Volatile private var currentDeviceKey: String? = null

    private var consumer: Consumer? = null
    private var connectionSubscription: Subscription? = null
    private var messageSubscription: Subscription? = null

    suspend fun connect(deviceKey: String) = connectMutex.withLock {
        val trimmedKey = deviceKey.trim()

        // Idempotent: skip if already connected with same key
        if (isConnected() && currentDeviceKey == trimmedKey) {
            Log.d(TAG, "Already connected with device key: $trimmedKey")
            return@withLock
        }

        val tokenResult = fetchDeviceToken()
        val deviceToken = when {
            tokenResult.isSuccess -> tokenResult.getOrNull().orEmpty()
            else -> {
                Log.e(TAG, "Failed to fetch Firebase token", tokenResult.exceptionOrNull())
                _connectionState.value = ConnectionState.ERROR
                return@withLock
            }
        }

        if (deviceToken.isBlank()) {
            Log.w(TAG, "Device token is empty")
            _connectionState.value = ConnectionState.ERROR
            return@withLock
        }

        _connectionState.value = ConnectionState.CONNECTING
        currentDeviceKey = trimmedKey

        withContext(Dispatchers.IO) {
            runCatching {
                tearDownConnection()

                val consumer = createConsumer(trimmedKey, deviceToken).also { this@ActionCableService.consumer = it }
                connectionSubscription = subscribeToConnectionEvents(consumer)
                messageSubscription = subscribeToMessageEvents(consumer)

                Log.d(TAG, "Connecting to ActionCable with device key: $trimmedKey")
                consumer.connect()
            }.onFailure { error ->
                Log.e(TAG, "Error connecting to ActionCable", error)
                _connectionState.value = ConnectionState.ERROR
                currentDeviceKey = null
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from ActionCable")
        currentDeviceKey = null
        tearDownConnection()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }

    fun sendHeartbeat() {
        runCatching {
            connectionSubscription?.perform("ping")
            Log.d(TAG, "Sent heartbeat")
        }.onFailure { error ->
            Log.e(TAG, "Error sending heartbeat", error)
        }
    }

    suspend fun notifyNewInboundMessage(from: String, to: String, body: String) {
        if (messageSubscription == null) {
            Log.w(TAG, "Cannot forward SMS; not connected to ActionCable")
            return
        }

        if (!settingsDataStore.isReceivingEnabled()) {
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

    fun sendMessageRequest(messageId: String) {
        val data = JsonObject().apply { addProperty("id", messageId) }
        runCatching { messageSubscription?.perform("message_send_requested", data) }
            .onFailure { error -> Log.e(TAG, "Error send message request: $messageId", error) }
    }

    private fun createConsumer(deviceKey: String, deviceToken: String): Consumer {
        val options = Consumer.Options().apply {
            reconnection = true
            reconnectionMaxAttempts = 5
            headers = mapOf(
                HEADER_DEVICE_KEY to deviceKey,
                HEADER_DEVICE_TOKEN to deviceToken,
            )
        }

        val targetUri = resolveSomlengUri()
        Log.d(TAG, "Using ActionCable endpoint: $targetUri")

        return ActionCable.createConsumer(targetUri, options)
    }

    private fun resolveSomlengUri(): URI {
        return URI(BuildConfig.SOMLENG_WS_URL)
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

                when (val messageType = payload["type"].safeAsString()) {
                    "message_send_request" -> {
                        Log.d(TAG, "Received send request from server: $data")

                        val messageId = payload["message_id"].safeAsString()
                        if (messageId.isNullOrBlank()) {
                            Log.w(TAG, "Missing messageId")
                            return@onReceived
                        }

                        sendMessageRequest(messageId)
                    }
                    "message_send_request_confirmed" -> {
                        Log.d(TAG, "Received confirmed message from server: $data")

                        sendSMS(data)
                    }
                    else -> {
                        Log.d(TAG, "Ignoring unsupported message type '$messageType'")
                    }
                }
            }
        }
    }

    private fun sendSMS(data: JsonElement) {
        val payload = runCatching { data.asJsonObject["message"].asJsonObject }.getOrNull()
        if (payload == null) {
            Log.w(TAG, "Invalid message data: $data")
            return
        }

        val messageId = payload["id"].safeAsString()
        val phoneNumber = payload["to"].safeAsString()
        val body = payload["body"].safeAsString()

        if (phoneNumber.isNullOrBlank() || body.isNullOrBlank()) {
            Log.w(TAG, "Missing SMS details in payload: $payload")
            return
        }

        serviceScope.launch {
            if (!settingsDataStore.isSendingEnabled()) {
                Log.i(
                    TAG,
                    "Outgoing Message is disabled."
                )
                sendDeliveryStatus(messageId, "failed")
                return@launch
            }

            sendSms(phoneNumber, body, messageId)
        }
    }

    private fun sendSms(phoneNumber: String, messageBody: String, messageId: String?) {
        runCatching {
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                messageBody,
                null,
                null
            )

            // TODO: Differentiate between sent vs delivered status by checking the delivery report `sentIntent` vs `deliverIntent`
            sendDeliveryStatus(messageId, "sent")

            Log.d(TAG, "SMS sent to $phoneNumber")
        }.onFailure { error ->
            Log.e(TAG, "Error sending SMS to $phoneNumber", error)
            sendDeliveryStatus(messageId, "failed")
        }
    }

    private fun sendDeliveryStatus(messageId: String?, status: String) {
        if (messageId == null) return

        val payload = JsonObject().apply {
            addProperty("id", messageId)
            addProperty("status", status)
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
        serviceScope.coroutineContext.cancelChildren()
    }

    private fun JsonElement?.safeAsString(): String? {
        return if (this == null || this.isJsonNull) null else runCatching { this.asString }.getOrNull()
    }

    private suspend fun fetchDeviceToken(): Result<String> {
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (continuation.isActive) {
                        continuation.resume(Result.success(token.orEmpty()))
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(error))
                    }
                }
        }
    }

    companion object {
        private const val TAG = "ActionCableService"
        private const val CONNECTION_CHANNEL = "SMSGatewayConnectionChannel"
        private const val MESSAGE_CHANNEL = "SMSMessageChannel"

        private const val HEADER_DEVICE_KEY = "X-Device-Key"
        private const val HEADER_DEVICE_TOKEN = "X-Device-Token"

        @Volatile
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
