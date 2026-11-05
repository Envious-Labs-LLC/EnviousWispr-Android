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
import java.util.concurrent.LinkedBlockingQueue
import java.io.File
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
    private val connections = LinkedBlockingQueue<IPolishService>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPolishService.Stub.asInterface(binder)
            connections.offer(checkNotNull(service))
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Before
    fun bindPolishService() {
        com.envi.wispr.models.FirebaseModelFixture.stageS1IfRequested(context)
        bound = context.bindService(
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
            }
        )

        assertTrue("Polish callback timed out", completed.await(90, TimeUnit.SECONDS))
        val result = assertNotNull(outcome).let { outcome!! }
        assertEquals(1L, result.requestId)
        assertEquals(PolishReason.POLISHED, result.reason)
        assertTrue("Unexpected engine: ${result.engine}", result.engine.startsWith("S1-mini by Superwhisper"))
        val words = result.text.lowercase()
        assertTrue("Polish returned blank text", words.isNotBlank())
        assertFalse("Filler was not removed: ${result.text}", words.startsWith("uh "))
        assertTrue("Product name was lost: ${result.text}", Regex("envious\\s*wispr").containsMatchIn(words))
        assertTrue("Person name was lost: ${result.text}", words.contains("saurabh"))
        assertTrue("Meaning was lost: ${result.text}", words.contains("useful"))
        assertTrue("Expected a standard-model backend, got: ${result.engine}", result.engine.endsWith("(GPU)") || result.engine.endsWith("(CPU)"))
        DebugLogger.log("S1DeviceTest", "engine=${result.engine} latencyMs=${result.latencyMs} chars=${result.text.length}")
    }
    @Test fun explicitCpuUsesCpuOnlyAfterQualificationOrKeepsTheDeterministicFloor() = explicitBackend(com.envi.wispr.processing.ProcessingBackend.CPU)
    @Test fun explicitGpuUsesGpuOnlyAfterQualificationOrKeepsTheDeterministicFloor() = explicitBackend(com.envi.wispr.processing.ProcessingBackend.GPU)

    /** Observability Contract plus Product Outcome: a timeout keeps words and a new process can polish again. */
    @Test fun localTimingsFollowBackToBackRequestsAndTimeoutRecovery() {
        val directory = File(context.filesDir, "debug").apply { mkdirs() }
        val budget = File(directory, "polish-deadline-ms")
        val stall = File(directory, "polish-stall-ms")
        val oldBudget = budget.takeIf(File::isFile)?.readBytes()
        val oldStall = stall.takeIf(File::isFile)?.readBytes()
        val policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT)
        val literal = "please send the report tomorrow morning"
        fun request(proxy: IPolishService, id: Long): PolishOutcome {
            val done = CountDownLatch(1)
            var answer: PolishOutcome? = null
            proxy.polishRequest(id, literal, true, true, false, policy, object : IPolishCallback.Stub() {
                override fun onOutcome(result: PolishOutcome?) {
                    answer = result
                    done.countDown()
                }
            })
            assertTrue("Request $id did not return its outcome", done.await(90, TimeUnit.SECONDS))
            return checkNotNull(answer).also { assertEquals(id, it.requestId) }
        }
        fun healthy(result: PolishOutcome) {
            assertEquals(PolishReason.POLISHED, result.reason)
            assertTrue(result.text.lowercase().contains("report"))
            assertTrue(result.text.lowercase().contains("tomorrow"))
            val measured = checkNotNull(result.diagnostics) { "No diagnostics crossed the real service boundary" }
            assertTrue(measured.queueMs >= 0)
            assertTrue(checkNotNull(measured.generationMs) >= 0)
            assertTrue(checkNotNull(measured.lastLoadMs) >= 0)
            assertTrue(checkNotNull(measured.lastLoadAgeMs) >= 0)
            assertNotNull(measured.runtime)
            DebugLogger.log("S1DeviceTest", "request=${result.requestId} timing=${measured.properties()}")
        }
        try {
            budget.delete()
            stall.delete()
            val initial = checkNotNull(connections.poll(10, TimeUnit.SECONDS)) { "Initial connection was not recorded" }
            initial.warmUpWithPolicy(policy)
            val first = request(initial, 101)
            healthy(first)
            val second = request(initial, 102)
            healthy(second)
            assertEquals(first.diagnostics?.lastLoadMs, second.diagnostics?.lastLoadMs)
            assertTrue(checkNotNull(second.diagnostics?.lastLoadAgeMs) >= checkNotNull(first.diagnostics?.lastLoadAgeMs))
            budget.writeText("1")
            stall.writeText("5000")
            val timedOut = request(initial, 103)
            assertEquals(PolishReason.LOCAL_TIMEOUT, timedOut.reason)
            assertEquals(literal, timedOut.text)
            assertNotNull(timedOut.diagnostics)
            assertNull("The debug stall happens before generation", timedOut.diagnostics?.generationMs)
            budget.delete()
            stall.delete()
            val fresh = checkNotNull(connections.poll(30, TimeUnit.SECONDS)) { "The timed-out service never reconnected" }
            fresh.warmUpWithPolicy(policy)
            healthy(request(fresh, 104))
        } finally {
            if (oldBudget == null) budget.delete() else budget.writeBytes(oldBudget)
            if (oldStall == null) stall.delete() else stall.writeBytes(oldStall)
        }
    }

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
