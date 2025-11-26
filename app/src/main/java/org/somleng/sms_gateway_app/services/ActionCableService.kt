package org.somleng.sms_gateway_app.services

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hosopy.actioncable.ActionCable
import com.hosopy.actioncable.Channel
import com.hosopy.actioncable.Consumer
import com.hosopy.actioncable.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.somleng.sms_gateway_app.BuildConfig
import org.somleng.sms_gateway_app.data.preferences.SettingsDataStore
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class ActionCableService @VisibleForTesting internal constructor(
    context: Context,
    dependencies: Dependencies
) {
    @VisibleForTesting
    internal data class Dependencies(
        val settings: GatewaySettings? = null,
        val smsDispatcher: SmsDispatcher? = null,
        val deliveryStatusSink: DeliveryStatusSink? = null,
        val serviceScope: CoroutineScope? = null,
        val ioContext: CoroutineContext = Dispatchers.IO,
        val registerSmsStatusReceivers: Boolean = true,
    )

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    private val appContext: Context = context.applicationContext ?: context

    // ActionCable
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var consumer: Consumer? = null
    private var connectionSubscription: Subscription? = null

    @VisibleForTesting
    internal var messageSubscription: Subscription? = null


    // Dependencies
    private val settings: GatewaySettings = dependencies.settings ?: DataStoreGatewaySettings(appContext)
    private val smsDispatcher: SmsDispatcher = dependencies.smsDispatcher ?: AndroidSmsDispatcher(appContext)
    private val deliveryStatusSink: DeliveryStatusSink = dependencies.deliveryStatusSink
        ?: ActionCableDeliveryStatusSink { messageSubscription }
    private val serviceScope: CoroutineScope = dependencies.serviceScope
        ?: CoroutineScope(SupervisorJob() + dependencies.ioContext)
    private val ioContext: CoroutineContext = dependencies.ioContext

    private val settingsDataStore = SettingsDataStore(appContext)
    private val pendingIntentRequestCode = AtomicInteger()

    private val connectMutex = Mutex()

    @Volatile
    private var currentDeviceKey: String? = null

    @VisibleForTesting
    internal var hasSendSmsPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }


    @VisibleForTesting
    internal val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)
            val phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
            when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "SMS sent broadcast received for $phoneNumber")
                    deliveryStatusSink.reportStatus(messageId, "sent")
                }

                else -> {
                    val reason = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Limit exceeded"
                        SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE -> "Radio not available"
                        SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED -> "SMS not allowed during call"
                        SmsManager.RESULT_RIL_SMS_SEND_FAIL_RETRY -> "Send failed, retry suggested"
                        else -> "Unknown error (code=$resultCode)"
                    }

                    Log.e(TAG, "SMS send failed for $phoneNumber: $reason")
                    deliveryStatusSink.reportStatus(messageId, "failed")
                }
            }
        }
    }

    @VisibleForTesting
    internal val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)
            val phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Delivery confirmed for $phoneNumber")
                deliveryStatusSink.reportStatus(messageId, "delivered")
            } else {
                Log.w(TAG, "Delivery not confirmed for $phoneNumber (code=$resultCode)")
            }
        }
    }

    init {
        if (dependencies.registerSmsStatusReceivers) {
            registerSmsStatusReceivers()
        }
    }

    // NOTE: Make sure there is only thread be able to connect
    suspend fun connect(deviceKey: String, forceReconnect: Boolean = false) = connectMutex.withLock {
        // Idempotent: skip if already connected with same key
        if (!forceReconnect && isConnected() && currentDeviceKey == deviceKey) {
            Log.d(TAG, "Already connected with device key: $deviceKey")
            return@withLock
        }

        _connectionState.value = ConnectionState.CONNECTING
        currentDeviceKey = deviceKey

        withContext(ioContext) {
            runCatching {
                tearDownConnection()

                val consumer = createConsumer(deviceKey).also { this@ActionCableService.consumer = it }
                connectionSubscription = subscribeToConnectionEvents(consumer)
                messageSubscription = subscribeToMessageEvents(consumer)

                Log.d(TAG, "Connecting to ActionCable with device key: $deviceKey")
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

        if (!settings.isReceivingEnabled()) {
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

    private suspend fun createConsumer(deviceKey: String): Consumer {
        val options = Consumer.Options().apply {
            reconnection = true
            reconnectionMaxAttempts = 5
            headers = mapOf(
                HEADER_DEVICE_KEY to deviceKey
            )
        }

        val targetUri = resolveSomlengUri()
        Log.d(TAG, "Using ActionCable endpoint: $targetUri")

        return ActionCable.createConsumer(targetUri, options)
    }

    private suspend fun resolveSomlengUri(): URI {
        val baseUrl = if (BuildConfig.ENVIRONMENT == "dev") {
            // In dev builds, use stored server host if available, otherwise fallback to BuildConfig
            settingsDataStore.getServerHost() ?: BuildConfig.SOMLENG_WS_URL
        } else {
            // In production, always use BuildConfig
            BuildConfig.SOMLENG_WS_URL
        }
        return URI("$baseUrl/cable")
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
            if (!settings.isSendingEnabled()) {
                Log.i(
                    TAG,
                    "Outgoing Message is disabled."
                )
                deliveryStatusSink.reportStatus(messageId, "failed")
                return@launch
            }

            sendSms(phoneNumber, body, messageId)
        }
    }

    @VisibleForTesting
    internal fun sendSms(phoneNumber: String, messageBody: String, messageId: String?) {
        if (!hasSendSmsPermission()) {
            Log.w(TAG, "Cannot send SMS to $phoneNumber because SEND_SMS permission is not granted")
            deliveryStatusSink.reportStatus(messageId, "failed")
            return
        }

        val sentIntent = createSmsPendingIntent(ACTION_SMS_SENT, messageId, phoneNumber)
        val deliveredIntent = createSmsPendingIntent(ACTION_SMS_DELIVERED, messageId, phoneNumber)

        runCatching {
            smsDispatcher.sendTextMessage(
                phoneNumber,
                null,
                messageBody,
                sentIntent,
                deliveredIntent
            )

            Log.d(TAG, "SMS sent to $phoneNumber")
        }.onFailure { error ->
            Log.e(TAG, "Error sending SMS to $phoneNumber", error)
            deliveryStatusSink.reportStatus(messageId, "failed")
        }
    }

    @VisibleForTesting
    internal fun sendDeliveryStatus(messageId: String?, status: String) {
        if (messageId != null) {
            deliveryStatusSink.reportStatus(messageId, status)
            Log.d(TAG, "Reported delivery status '$status' for message $messageId")
        }
    }

    private fun tearDownConnection() {
        connectionSubscription = null
        messageSubscription = null
        consumer?.disconnect()
        consumer = null
        serviceScope.coroutineContext.cancelChildren()
    }

    private fun registerSmsStatusReceivers() {
        ContextCompat.registerReceiver(
            appContext,
            smsSentReceiver,
            IntentFilter(ACTION_SMS_SENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            appContext,
            smsDeliveredReceiver,
            IntentFilter(ACTION_SMS_DELIVERED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    @VisibleForTesting
    internal fun createSmsPendingIntent(action: String, messageId: String?, phoneNumber: String): PendingIntent {
        val requestCode = pendingIntentRequestCode.getAndIncrement()
        val intent = Intent(action).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    private fun JsonElement?.safeAsString(): String? {
        return if (this == null || this.isJsonNull) null else runCatching { this.asString }.getOrNull()
    }

    companion object {
        private const val TAG = "ActionCableService"
        private const val CONNECTION_CHANNEL = "SMSGatewayConnectionChannel"
        private const val MESSAGE_CHANNEL = "SMSMessageChannel"
        private const val ACTION_SMS_SENT = "${BuildConfig.APPLICATION_ID}.ACTION_SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "${BuildConfig.APPLICATION_ID}.ACTION_SMS_DELIVERED"
        private const val EXTRA_MESSAGE_ID = "extra_message_id"
        private const val EXTRA_PHONE_NUMBER = "extra_phone_number"

        private const val HEADER_DEVICE_KEY = "X-Device-Key"

        @Volatile
        private var instance: ActionCableService? = null

        @Synchronized
        fun getInstance(context: Context): ActionCableService {
            if (instance == null) {
                instance = ActionCableService(context.applicationContext, Dependencies())
            }
            return instance!!
        }
    }
}
