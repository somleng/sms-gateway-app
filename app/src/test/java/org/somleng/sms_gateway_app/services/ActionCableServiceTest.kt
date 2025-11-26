package org.somleng.sms_gateway_app.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.hosopy.actioncable.Subscription
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActionCableServiceTest {

    private lateinit var dependencies: ActionCableService.Dependencies
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var messageSubscription: Subscription
    private lateinit var service: ActionCableService


    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        messageSubscription = mockk(relaxed = true)

        dependencies = ActionCableService.Dependencies(
            settings = mockk(),
            smsDispatcher = mockk(relaxed = true),
            deliveryStatusSink = mockk(relaxed = true),
            serviceScope = CoroutineScope(testDispatcher),
            ioContext = Dispatchers.Unconfined,
            registerSmsStatusReceivers = false
        )

        service = ActionCableService(
            context = ApplicationProvider.getApplicationContext(),
            dependencies = dependencies
        )
        service.messageSubscription = messageSubscription
    }

    @Test
    fun `notifyNewInboundMessage forwards SMS when receiving is enabled`() = runSuspendTest {
        stubReceiving(enabled = true)

        notifyInbound(
            from = "1234",
            to = "85510123456",
            body = "Hello, world!"
        )

        verifyInboundForwarded(
            from = "1234",
            to = "85510123456",
            body = "Hello, world!"
        )
    }

    @Test
    fun `notifyNewInboundMessage skips forwarding when receiving is disabled`() = runSuspendTest {
        stubReceiving(enabled = false)

        notifyInbound(
            from = "1234",
            to = "85510123456",
            body = "Hello, world!"
        )

        verifyNoInboundForwarded()
    }

    @Test
    fun `notifyNewInboundMessage skips forwarding when not connected`() = runSuspendTest {
        service.messageSubscription = null
        stubReceiving(enabled = true)

        notifyInbound(
            from = "1234",
            to = "85510123456",
            body = "Hello, world!"
        )

        verifyNoInboundForwarded()
    }

    @Test
    fun `sendDeliveryStatus ignores null message IDs`() {
        service.sendDeliveryStatus(null, "sent")

        verify(exactly = 0) {
            dependencies.deliveryStatusSink?.reportStatus(any(), any())
        }
    }

    @Test
    fun `sendDeliveryStatus reports status for valid message ID`() {
        service.sendDeliveryStatus("msg-123", "sent")

        verify {
            dependencies.deliveryStatusSink?.reportStatus("msg-123", "sent")
        }
    }

    @Test
    fun `sendMessageRequest sends correct payload`() {
        service.sendMessageRequest("msg-123")

        verify {
            messageSubscription.perform(eq("message_send_requested"), match<JsonObject> { payload ->
                payload.string("id") == "msg-123"
            })
        }
    }

    @Test
    fun `connection state starts as DISCONNECTED`() {
        assertEquals(ActionCableService.ConnectionState.DISCONNECTED, service.connectionState.value)
        assertFalse(service.isConnected())
    }

    @Test
    fun `sendSms does not dispatch when SEND_SMS permission is missing`() {
        service.hasSendSmsPermission = { false }

        service.sendSms(
            phoneNumber = "+85516701721",
            messageBody = "Hello",
            messageId = "msg-123"
        )

        verify(exactly = 0) {
            dependencies.smsDispatcher?.sendTextMessage(any(), any(), any(), any(), any())
        }
        verify {
            dependencies.deliveryStatusSink?.reportStatus("msg-123", "failed")
        }
    }

    @Test
    fun `sendSms dispatches when SEND_SMS permission is granted`() {
        service.hasSendSmsPermission = { true }

        service.sendSms(
            phoneNumber = "+85516701721",
            messageBody = "Hello",
            messageId = "msg-456"
        )

        verify {
            dependencies.smsDispatcher?.sendTextMessage(
                "+85516701721",
                null,
                "Hello",
                any(),
                any()
            )
        }
    }

    private fun runSuspendTest(block: suspend TestScope.() -> Unit) = runTest(testDispatcher) { block() }

    private fun stubReceiving(enabled: Boolean) {
        coEvery { dependencies.settings?.isReceivingEnabled() } returns enabled
    }

    private suspend fun notifyInbound(
        from: String,
        to: String,
        body: String
    ) = service.notifyNewInboundMessage(from, to, body)

    private fun verifyInboundForwarded(
        from: String,
        to: String,
        body: String
    ) {
        verify {
            messageSubscription.perform(eq("received"), match<JsonObject> { payload ->
                payload.string("from") == from &&
                        payload.string("to") == to &&
                        payload.string("body") == body
            })
        }
    }

    private fun verifyNoInboundForwarded() {
        verify(exactly = 0) {
            messageSubscription.perform(any(), any<JsonObject>())
        }
    }

    private fun JsonObject.string(key: String) = get(key).asString
}
