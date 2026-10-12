package com.envi.wispr.polish

import com.envi.wispr.processing.*
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: the next take must use its new backend, not a cached old model. */
class S1LoadedConfigurationTest {
    private val context = "a".repeat(64)
    private val qualification = ProcessingQualification.of(context, setOf(ProcessingBackend.GPU, ProcessingBackend.CPU))
    private fun config(pref: ProcessingPreference = ProcessingPreference.DEFAULT, ctx: String = context) = S1LoadConfiguration(ctx, pref, qualification)
    @Test fun aNewerGpuCheckInvalidatesAnOlderFailedGpuDecisionInACpuFallback() {
        val pref = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU))
        val resident = S1LoadedConfiguration()
        resident.ensure(config(pref), failures = { setOf(ProcessingBackend.GPU) }, failureStamp = { ProcessingEvidenceStamp(1, 100) }) { ProcessingBackend.CPU }
        val restored = qualification.observed(setOf(ProcessingBackend.GPU), emptySet(), false, 0, ProcessingEvidenceStamp(1, 200))
        assertFalse(resident.matches(S1LoadConfiguration(context, pref, restored)))
    }
    @Test fun emptyEligibleOrderClosesTheOldInstanceWithoutConstructingAnother() {
        val pref = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.GPU))
        val resident = S1LoadedConfiguration(); var instances = 0; var closes = 0; var loads = 0
        resident.ensure(config(pref)) { loads++; instances++; ProcessingBackend.GPU }
        val absent = S1LoadConfiguration(context, pref, ProcessingQualification.of(context, emptySet()))
        assertTrue(runCatching { resident.ensure(absent, release = { closes++; instances-- }) { loads++; instances++; ProcessingBackend.CPU } }.isFailure)
        assertEquals(1, closes); assertEquals(1, loads); assertEquals(0, instances); assertNull(resident.loaded)
        assertFalse(resident.matches(absent))
    }
    @Test fun customRetryRevisitsAProvenFailedGpuButNotAnUnseenGpu() {
        val pref = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU))
        val failed = qualification.observed(setOf(ProcessingBackend.CPU), setOf(ProcessingBackend.GPU), false, 0, ProcessingEvidenceStamp(1, 100))
        assertEquals(listOf(ProcessingBackend.CPU), S1LoadConfiguration(context, pref, failed).candidates)
        assertEquals(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU), S1LoadConfiguration(context, pref.retry(), failed).candidates)
        val unseen = ProcessingQualification.of(context, setOf(ProcessingBackend.CPU)).observed(emptySet(), setOf(ProcessingBackend.GPU), false, 0, ProcessingEvidenceStamp(1, 100))
        assertEquals(listOf(ProcessingBackend.CPU), S1LoadConfiguration(context, pref.retry(), unseen).candidates)
        assertEquals(failed, ProcessingQualification.decode(failed.encode()))
    }
    @Test fun recordingAProviderFailureDoesNotReloadItsHealthyFallbackUntilRetry() {
        val pref = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU))
        val resident = S1LoadedConfiguration()
        resident.ensure(config(pref), failures = { setOf(ProcessingBackend.GPU) }) { ProcessingBackend.CPU }
        val failed = qualification.observed(setOf(ProcessingBackend.CPU), setOf(ProcessingBackend.GPU), false, 0, ProcessingEvidenceStamp(1, 100))
        val next = S1LoadConfiguration(context, pref, failed)
        assertTrue(resident.matches(next))
        resident.ensure(next) { fail("Reloaded a healthy fallback after recording its failure"); ProcessingBackend.CPU }
        assertFalse(resident.matches(next.copy(preference = pref.retry())))
    }
    @Test fun newlyProvenHigherPriorityBackendCannotHideBehindAnOldCpuInstance() {
        val pref = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU))
        val resident = S1LoadedConfiguration()
        resident.ensure(S1LoadConfiguration(context, pref, ProcessingQualification.of(context, setOf(ProcessingBackend.CPU)))) { ProcessingBackend.CPU }
        assertFalse(resident.matches(config(pref)))
    }
    @Test fun replacingAnInstallationForbidsReuseOfItsOldNativeInstance() {
        val resident = S1LoadedConfiguration()
        val installed = config().copy(artifactStamp = "original")
        resident.ensure(installed) { ProcessingBackend.GPU }
        assertFalse(resident.matches(installed.copy(artifactStamp = "replacement")))
    }
    @Test fun bothQueuedWarmArrivalOrdersHonorTheFinalCapturedConfiguration() {
        val cpu = config(ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU)))
        for (order in listOf(listOf(config(), cpu), listOf(cpu, config()))) {
            val resident = S1LoadedConfiguration()
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            val finished = java.util.concurrent.CountDownLatch(1)
            try {
                order.forEach { requested -> worker.execute { resident.ensure(requested) { it.first() } } }
                worker.execute { finished.countDown() }
                assertTrue("Queued configurations did not drain", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(order.last(), resident.loaded!!.requested)
                assertEquals(order.last().candidates.first(), resident.loaded!!.backend)
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun readyOldModelDoesNotSuppressNewPolicy() {
        val resident = S1LoadedConfiguration(); val loads = mutableListOf<List<ProcessingBackend>>()
        resident.ensure(config()) { loads += it; ProcessingBackend.GPU }
        val cpu = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU, ProcessingBackend.GPU))
        resident.ensure(config(cpu)) { loads += it; it.first() }
        assertEquals(listOf(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU), listOf(ProcessingBackend.CPU, ProcessingBackend.GPU)), loads)
        assertEquals(ProcessingBackend.CPU, resident.loaded!!.backend)
        assertFalse(resident.matches(config()))
    }
    @Test fun healthyFallbackIsReusedUntilExplicitRetry() {
        val resident = S1LoadedConfiguration(); var loads = 0
        val initial = config()
        resident.ensure(initial, failures = { setOf(ProcessingBackend.GPU) }) { loads++; ProcessingBackend.CPU }
        resident.ensure(initial) { fail("Reloaded known fallback"); ProcessingBackend.GPU }
        resident.ensure(config(ProcessingPreference.DEFAULT.retry())) { loads++; ProcessingBackend.GPU }
        assertEquals(2, loads)
    }
    @Test fun failedReplacementCannotReuseOldInstanceKey() {
        val resident = S1LoadedConfiguration()
        resident.ensure(config()) { ProcessingBackend.GPU }
        assertTrue(runCatching { resident.ensure(config(ProcessingPreference.DEFAULT.retry())) { error("load failed") } }.isFailure)
        assertNull(resident.loaded)
    }
    @Test fun platformChangeInvalidatesManualQualification() {
        val manual = ProcessingPreference.DEFAULT.custom(listOf(ProcessingBackend.CPU))
        assertTrue(config(manual, "b".repeat(64)).candidates.isEmpty())
    }
    @Test fun automaticNeverEntersExperimentalNpu() {
        assertEquals(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU), config().candidates)
    }
    @Test fun requestedAndActualBackendRemainDistinct() {
        val resident = S1LoadedConfiguration()
        val loaded = resident.ensure(config(), failures = { setOf(ProcessingBackend.GPU) }) { ProcessingBackend.CPU }
        assertEquals(ProcessingBackend.GPU, loaded.requested.candidates.first())
        assertEquals(ProcessingBackend.CPU, loaded.backend)
    }
}
