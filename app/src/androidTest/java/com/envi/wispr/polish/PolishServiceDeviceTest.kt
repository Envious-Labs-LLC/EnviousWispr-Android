package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    }

    @After
    fun unbindPolishService() {
        if (bound) context.unbindService(connection)
    }

    @Test
    fun s1PolishesTextOnAutomaticStandardBackend() {
        service?.warmUpWithPolicy(PolishPolicy.LocalS1(S1ControlSettings.DEFAULT))
        val completed = CountDownLatch(1)
        var outcome: PolishOutcome? = null

        service?.polishRequest(
            1L,
            "uh enviouswispr works with saurabh and it is really really useful",
            true,
            true,
            false,
            PolishPolicy.LocalS1(S1ControlSettings.DEFAULT),
            object : IPolishCallback.Stub() {
                override fun onOutcome(delivered: PolishOutcome?) {
                    outcome = delivered
                    completed.countDown()
                }

                override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) = Unit

                override fun onError(message: String?) = Unit
            }
        )

        assertTrue("Polish callback timed out", completed.await(90, TimeUnit.SECONDS))
        val result = assertNotNull(outcome).let { outcome!! }
        assertEquals(1L, result.requestId)
        assertEquals(PolishReason.POLISHED, result.reason)
        assertTrue("Unexpected engine: ${result.engine}", result.engine.startsWith("S1-mini by Superwhisper"))
        assertFalse("Filler was not removed: ${result.text}", result.text.lowercase().startsWith("uh "))
        assertTrue("Expected a standard-model backend, got: ${result.engine}", result.engine.endsWith("(GPU)") || result.engine.endsWith("(CPU)"))
        DebugLogger.log("S1DeviceTest", "engine=${result.engine} latencyMs=${result.latencyMs} chars=${result.text.length}")
    }
    @Test fun explicitCpuUsesCpuOnlyAfterQualificationOrKeepsTheDeterministicFloor() = explicitBackend(com.envi.wispr.processing.ProcessingBackend.CPU)
    @Test fun explicitGpuUsesGpuOnlyAfterQualificationOrKeepsTheDeterministicFloor() = explicitBackend(com.envi.wispr.processing.ProcessingBackend.GPU)

    private fun explicitBackend(backend: com.envi.wispr.processing.ProcessingBackend) {
        val checked = CountDownLatch(1)
        var qualification: com.envi.wispr.processing.ProcessingCheckResult? = null
        checkNotNull(service).qualifyProcessing(7, backend.wire, object : com.envi.wispr.processing.IProcessingCheckCallback.Stub() {
            override fun onChecked(result: com.envi.wispr.processing.ProcessingCheckResult?) { qualification = result; checked.countDown() }
        })
        assertTrue("Backend qualification gave no terminal answer", checked.await(90, TimeUnit.SECONDS))
        val result = checkNotNull(qualification)
        val available = result.status == com.envi.wispr.processing.ProcessingCheckStatus.AVAILABLE
        val evidence = com.envi.wispr.processing.ProcessingQualification.of(result.contextId, if (available) setOf(backend) else emptySet())
        val preference = com.envi.wispr.processing.ProcessingPreference.DEFAULT.custom(listOf(backend))
        val policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT, preference, evidence)
        checkNotNull(service).warmUpWithPolicy(policy)
        val received = CountDownLatch(1)
        var answer: PolishOutcome? = null
        val literal = "please send the report tomorrow morning"
        checkNotNull(service).polishRequest(8, literal, true, true, false, policy, object : IPolishCallback.Stub() {
            override fun onOutcome(result: PolishOutcome?) { answer = result; received.countDown() }
            override fun onResult(text: String?, engine: String?, latencyMs: Long) = Unit
            override fun onError(message: String?) = Unit
        })
        assertTrue("Captured explicit policy did not return words", received.await(90, TimeUnit.SECONDS))
        val outcome = checkNotNull(answer)
        if (available) {
            assertEquals(PolishReason.POLISHED, outcome.reason)
            assertEquals(backend, outcome.processing?.backend)
            assertEquals(true, outcome.processing?.acceptedLocalText)
        } else {
            assertEquals(PolishReason.LOCAL_NOT_READY, outcome.reason)
            assertEquals(literal, outcome.text)
            assertNull(outcome.processing?.backend)
            assertEquals(false, outcome.processing?.acceptedLocalText)
        }
        DebugLogger.log("S1DeviceTest", "requested=${backend.wire} qualification=${result.status} reason=${outcome.reason} actual=${outcome.processing?.backend}")
    }

}
