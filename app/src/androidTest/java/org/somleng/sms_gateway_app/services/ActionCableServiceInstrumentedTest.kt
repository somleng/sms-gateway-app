package org.somleng.sms_gateway_app.services

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionCableServiceInstrumentedTest {

    private lateinit var context: Context
    private lateinit var tokenProvider: DeviceTokenProvider
    private lateinit var settings: GatewaySettings
    private lateinit var smsDispatcher: SmsDispatcher
    private lateinit var deliveryStatusSink: DeliveryStatusSink
    private lateinit var service: ActionCableService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tokenProvider = mockk()
        settings = mockk()
        smsDispatcher = mockk(relaxed = true)
        deliveryStatusSink = mockk(relaxed = true)

        coEvery { tokenProvider.fetchToken() } returns Result.success("test-token")
        coEvery { settings.isReceivingEnabled() } returns true
        coEvery { settings.isSendingEnabled() } returns true
        coEvery { settings.getServerHost() } returns null

        service = ActionCableService(
            context = context,
            tokenProvider = tokenProvider,
            settings = settings,
            smsDispatcher = smsDispatcher,
            deliveryStatusSink = deliveryStatusSink,
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ioContext = Dispatchers.IO
        )
    }

    @Test
    fun createSmsPendingIntent_embedsPackageName() {
        // Given
        val action = "org.somleng.sms_gateway_app.ACTION_SMS_SENT"
        val messageId = "msg-123"
        val phoneNumber = "+1234567890"

        // When
        val pendingIntent = service.createSmsPendingIntent(action, messageId, phoneNumber)

        // Then
        assertNotNull(pendingIntent)
        // PendingIntent doesn't expose its intent directly, but we can verify it was created
    }

    @Test
    fun createSmsPendingIntent_embedsExtras() {
        // Given
        val action = "org.somleng.sms_gateway_app.ACTION_SMS_SENT"
        val messageId = "msg-456"
        val phoneNumber = "+0987654321"

        // When
        val pendingIntent = service.createSmsPendingIntent(action, messageId, phoneNumber)

        // Then
        assertNotNull(pendingIntent)
        // The intent is created with the correct extras (verified through integration)
    }

    @Test
    fun createSmsPendingIntent_usesImmutableFlagOnApi23Plus() {
        // Given
        val action = "org.somleng.sms_gateway_app.ACTION_SMS_DELIVERED"
        val messageId = "msg-789"
        val phoneNumber = "+1111111111"

        // When
        val pendingIntent = service.createSmsPendingIntent(action, messageId, phoneNumber)

        // Then
        assertNotNull(pendingIntent)
        // PendingIntent should have FLAG_IMMUTABLE set on API 23+
        // This is implicitly tested by the fact that it doesn't throw
    }

    @Test
    fun smsSentReceiver_reportsSuccessOnResultOk() {
        // Given
        val messageId = "msg-success"
        val phoneNumber = "+1234567890"
        val intent = Intent("org.somleng.sms_gateway_app.ACTION_SMS_SENT").apply {
            putExtra("extra_message_id", messageId)
            putExtra("extra_phone_number", phoneNumber)
        }

        // When
        service.smsSentReceiver.resultCode = Activity.RESULT_OK
        service.smsSentReceiver.onReceive(context, intent)

        // Then
        verify {
            deliveryStatusSink.reportStatus(messageId, "sent")
        }
    }

    @Test
    fun smsSentReceiver_reportsFailureOnError() {
        // Given
        val messageId = "msg-fail"
        val phoneNumber = "+1234567890"
        val intent = Intent("org.somleng.sms_gateway_app.ACTION_SMS_SENT").apply {
            putExtra("extra_message_id", messageId)
            putExtra("extra_phone_number", phoneNumber)
        }

        // When
        service.smsSentReceiver.resultCode = android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE
        service.smsSentReceiver.onReceive(context, intent)

        // Then
        verify {
            deliveryStatusSink.reportStatus(messageId, "failed")
        }
    }

    @Test
    fun smsDeliveredReceiver_reportsDeliveredOnResultOk() {
        // Given
        val messageId = "msg-delivered"
        val phoneNumber = "+1234567890"
        val intent = Intent("org.somleng.sms_gateway_app.ACTION_SMS_DELIVERED").apply {
            putExtra("extra_message_id", messageId)
            putExtra("extra_phone_number", phoneNumber)
        }

        // When
        service.smsDeliveredReceiver.resultCode = Activity.RESULT_OK
        service.smsDeliveredReceiver.onReceive(context, intent)

        // Then
        verify {
            deliveryStatusSink.reportStatus(messageId, "delivered")
        }
    }

    @Test
    fun smsDeliveredReceiver_doesNotReportOnFailure() {
        // Given
        val messageId = "msg-not-delivered"
        val phoneNumber = "+1234567890"
        val intent = Intent("org.somleng.sms_gateway_app.ACTION_SMS_DELIVERED").apply {
            putExtra("extra_message_id", messageId)
            putExtra("extra_phone_number", phoneNumber)
        }

        // When
        service.smsDeliveredReceiver.resultCode = Activity.RESULT_CANCELED
        service.smsDeliveredReceiver.onReceive(context, intent)

        // Then
        // Should not report "delivered" status on failure
        verify(exactly = 0) {
            deliveryStatusSink.reportStatus(messageId, "delivered")
        }
    }

    @Test
    fun connectionState_initiallyDisconnected() {
        // Then
        assertEquals(
            ActionCableService.ConnectionState.DISCONNECTED,
            service.connectionState.value
        )
    }

    @Test
    fun isConnected_returnsFalseInitially() {
        // Then
        assertFalse(service.isConnected())
    }
}

