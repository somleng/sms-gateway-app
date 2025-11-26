package org.somleng.sms_gateway_app.services

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ActionCableServiceInstrumentedTest {

    private lateinit var dependencies: ActionCableService.Dependencies
    private lateinit var service: ActionCableService

    @Before
    fun setup() {
        dependencies = ActionCableService.Dependencies(
            settings = mockk(),
            smsDispatcher = mockk(relaxed = true),
            deliveryStatusSink = mockk(relaxed = true),
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ioContext = Dispatchers.IO,
        )

        coEvery { dependencies.settings?.isReceivingEnabled() } returns true
        coEvery { dependencies.settings?.isSendingEnabled() } returns true
        coEvery { dependencies.settings?.getServerHost() } returns null

        service = ActionCableService(
            context = ApplicationProvider.getApplicationContext(),
            dependencies = dependencies
        )
    }

    @Test
    fun createSmsPendingIntent_embedsMessageAndPhoneExtras() {
        val action = "org.somleng.sms_gateway_app.ACTION_SMS_SENT"
        val pendingIntent = service.createSmsPendingIntent(
            action = action,
            messageId = "msg-456",
            phoneNumber = "+0987654321"
        )

        val broadcast = captureBroadcast(action) { pendingIntent.send() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(context.packageName, broadcast.`package`)
        assertEquals("msg-456", broadcast.getStringExtra("extra_message_id"))
        assertEquals("+0987654321", broadcast.getStringExtra("extra_phone_number"))
    }

    @Test
    fun createSmsPendingIntent_usesImmutableFlagWhenApi23Plus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val action = "org.somleng.sms_gateway_app.ACTION_SMS_DELIVERED"
        val pendingIntent = service.createSmsPendingIntent(
            action = action,
            messageId = "msg-789",
            phoneNumber = "+1111111111"
        )

        val overrideIntent = Intent(action).apply {
            putExtra("extra_message_id", "overridden")
            putExtra("extra_phone_number", "+0000000000")
        }

        val broadcast = captureBroadcast(action) {
            pendingIntent.send(
                ApplicationProvider.getApplicationContext(),
                Activity.RESULT_OK,
                overrideIntent
            )
        }

        assertEquals("msg-789", broadcast.getStringExtra("extra_message_id"))
        assertEquals("+1111111111", broadcast.getStringExtra("extra_phone_number"))
    }

    @Test
    fun connectionState_startsAsDisconnected() {
        assertEquals(
            ActionCableService.ConnectionState.DISCONNECTED,
            service.connectionState.value
        )
    }

    @Test
    fun isConnected_returnsFalseWhenDisconnected() {
        assertFalse(service.isConnected())
    }

    private fun captureBroadcast(action: String, trigger: () -> Unit): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val latch = CountDownLatch(1)
        val receivedIntent = AtomicReference<Intent?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action) {
                    receivedIntent.set(intent)
                    latch.countDown()
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            trigger()
            assertTrue("Timed out waiting for broadcast for $action", latch.await(2, TimeUnit.SECONDS))
        } finally {
            context.unregisterReceiver(receiver)
        }

        return requireNotNull(receivedIntent.get())
    }
}
