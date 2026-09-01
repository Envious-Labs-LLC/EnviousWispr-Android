package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Product Outcome: the shipped local model on the real runtime polishes a dictation through the v2 binder surface. */
@RunWith(AndroidJUnit4::class)
class PolishServiceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connected = CountDownLatch(1)
    private var service: IPolishService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPolishService.Stub.asInterface(binder)
            bound = true
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    @Before
    fun bindPolishService() {
        context.bindService(
            Intent(context, PolishService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        assertTrue("PolishService did not connect", connected.await(10, TimeUnit.SECONDS))
        service?.warmUpWithPolicy(PolishPolicy.LocalS1)

        val deadline = System.currentTimeMillis() + 30_000
        while (service?.isLocalModelReady != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("S1-mini did not become ready: ${service?.localModelStatus()}", service?.isLocalModelReady == true)
    }

    @After
    fun unbindPolishService() {
        if (bound) context.unbindService(connection)
    }

    @Test
    fun s1PolishesTextOnNpu() {
        val completed = CountDownLatch(1)
        var outcome: PolishOutcome? = null

        service?.polishRequest(
            1L,
            "um enviouswispr works with saurabh and it is really really useful",
            true,
            true,
            false,
            PolishPolicy.LocalS1,
            object : IPolishCallback.Stub() {
                override fun onOutcome(delivered: PolishOutcome?) {
                    outcome = delivered
                    completed.countDown()
                }

                override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) = Unit

                override fun onError(message: String?) = Unit
            }
        )

        assertTrue("Polish callback timed out", completed.await(30, TimeUnit.SECONDS))
        val result = assertNotNull(outcome).let { outcome!! }
        assertEquals(1L, result.requestId)
        assertEquals(PolishReason.POLISHED, result.reason)
        assertTrue("Unexpected engine: ${result.engine}", result.engine.startsWith("S1-mini by Superwhisper"))
        assertFalse("Filler was not removed: ${result.text}", result.text.lowercase().startsWith("um "))
        assertTrue("Expected the proven NPU backend, got: ${result.engine}", result.engine.endsWith("(NPU)"))
        Log.i("S1DeviceTest", "engine=${result.engine} latencyMs=${result.latencyMs} chars=${result.text.length}")
    }
}
