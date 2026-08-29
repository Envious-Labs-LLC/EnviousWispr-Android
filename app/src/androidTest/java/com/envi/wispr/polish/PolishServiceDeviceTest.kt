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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        service?.warmUp()

        val deadline = System.currentTimeMillis() + 30_000
        while (service?.isReady != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("S1-mini did not become ready: ${service?.status}", service?.isReady == true)
    }

    @After
    fun unbindPolishService() {
        if (bound) context.unbindService(connection)
    }

    @Test
    fun s1PolishesTextOnNpu() {
        val completed = CountDownLatch(1)
        var result: String? = null
        var engine: String? = null
        var latencyMs = -1L

        service?.polish(
            "um enviouswispr works with saurabh and it is really really useful",
            true,
            true,
            false,
            object : IPolishCallback.Stub() {
                override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) {
                    result = text
                    engine = usedEngine
                    latencyMs = measuredLatencyMs
                    completed.countDown()
                }

                override fun onError(message: String?) {
                    completed.countDown()
                }
            }
        )

        assertTrue("Polish callback timed out", completed.await(30, TimeUnit.SECONDS))
        assertNotNull(result)
        assertTrue("Unexpected engine: $engine", engine?.startsWith("S1-mini by Superwhisper") == true)
        assertFalse("Filler was not removed: $result", result!!.lowercase().startsWith("um "))
        assertTrue("Expected the proven NPU backend, got: $engine", engine?.endsWith("(NPU)") == true)
        Log.i("S1DeviceTest", "engine=$engine latencyMs=$latencyMs chars=${result!!.length}")
    }
}
