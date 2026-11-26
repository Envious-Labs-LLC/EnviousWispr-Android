package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Product Outcome at the logic layer (#561): when this fails, the user sees the bubble drawn for, or the
 * tracker adopting, an editor read before a newer focus replaced it. It drives the real
 * [EditorTargetTracker]'s stale-answer guard: a probe's answer is adopted only while the remembered editor
 * is still the one the probe copied. The service is the JVM's default-valued stub, so every accessibility
 * read answers nothing; a found or still-focused editor needs real nodes and is not staged here (the device
 * harness owns that boundary). What IS real is the epoch every write of the remembered editor bumps.
 */
class EditorTargetTrackerProbeTest {

    private val service = object : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }
    private val tracker = EditorTargetTracker(service)

    /** The blocking half on its own thread, as the bubble host runs it. */
    private fun probeOffThisThread(probe: FieldProbe): FieldAdoption {
        val thread = Executors.newSingleThreadExecutor()
        try {
            return thread.submit<FieldAdoption> { probe.probe(discover = true, windows = emptyList()) }.get(5, TimeUnit.SECONDS)
        } finally {
            thread.shutdownNow()
        }
    }

    @Test
    fun anAnswerWithNoChangeInBetweenIsAdopted() {
        val adoption = probeOffThisThread(tracker.fieldProbe())
        assertEquals(FieldProbeOutcome.Current(null), adoption.adopt())
    }

    @Test
    fun anAnswerReadBeforeTheRememberedEditorChangedIsStale() {
        val adoption = probeOffThisThread(tracker.fieldProbe())
        // On main, while the probe's answer travelled back: the remembered editor was replaced.
        tracker.clearTarget()
        assertEquals(FieldProbeOutcome.Stale, adoption.adopt())
    }

    @Test
    fun aChangeBeforeTheProbeEvenRanStillMakesItsAnswerStale() {
        val probe = tracker.fieldProbe()
        tracker.clearTarget()
        assertEquals(FieldProbeOutcome.Stale, probeOffThisThread(probe).adopt())
    }

    @Test
    fun aCloseInBetweenMakesTheAnswerStale() {
        val adoption = probeOffThisThread(tracker.fieldProbe())
        tracker.close()
        assertEquals(FieldProbeOutcome.Stale, adoption.adopt())
    }

    @Test
    fun aFreshProbeAfterTheChangeIsAdoptedAgain() {
        val stale = probeOffThisThread(tracker.fieldProbe())
        tracker.clearTarget()
        assertEquals(FieldProbeOutcome.Stale, stale.adopt())
        assertEquals(FieldProbeOutcome.Current(null), probeOffThisThread(tracker.fieldProbe()).adopt())
    }

    @Test
    fun theAdoptionDecisionIsStaleFirstThenAdmissionReadOnMain() {
        // The rows a stub service cannot stage (a refreshed or discovered editor needs real nodes): a stale
        // answer adopts nothing whatever it found; a candidate is adopted only when re-admitted on main, so a
        // field of our own withdrawn while the probe ran is not adopted; no candidate is no field.
        assertEquals(FieldAdoptionStep.STALE, fieldAdoptionStep(epochCurrent = false, candidateAdmitted = true))
        assertEquals(FieldAdoptionStep.STALE, fieldAdoptionStep(epochCurrent = false, candidateAdmitted = null))
        assertEquals(FieldAdoptionStep.ADOPT, fieldAdoptionStep(epochCurrent = true, candidateAdmitted = true))
        assertEquals(FieldAdoptionStep.NO_FIELD, fieldAdoptionStep(epochCurrent = true, candidateAdmitted = false))
        assertEquals(FieldAdoptionStep.NO_FIELD, fieldAdoptionStep(epochCurrent = true, candidateAdmitted = null))
    }
}
