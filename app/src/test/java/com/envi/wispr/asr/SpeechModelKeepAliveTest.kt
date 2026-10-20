package com.envi.wispr.asr

import com.envi.wispr.asr.SpeechModelKeepAlive.Deadline
import com.envi.wispr.settings.SpeechModelUnload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#37): when this fails, the next dictation waits 4 to 7 s for a model reload the user chose to
 * avoid, or 700 MB stays loaded after the user chose to free it, or a dead speech process is brought back idle.
 * The holder runs against a fake binding port, a fake clock and fake timers; the device run owns Android.
 */
class SpeechModelKeepAliveTest {
    private var now = 0L
    private val binds = mutableListOf<FakeRegistration>()
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private val log = mutableListOf<String>()

    private inner class FakeRegistration(val onGone: () -> Unit) : SpeechModelKeepAlive.Registration {
        var unbound = false
        override fun unbind() { unbound = true }
    }

    private var refuse = false
    private val port = object : SpeechModelKeepAlive.Port {
        override fun bind(onConnected: () -> Unit, onGone: () -> Unit): SpeechModelKeepAlive.Registration? {
            if (refuse) return null
            return FakeRegistration(onGone).also { binds += it; onConnected() }
        }
    }
    private val timers = object : SpeechModelKeepAlive.Timers {
        override fun schedule(delayMs: Long, action: Runnable) { scheduled += (now + delayMs) to action }
        override fun cancel(action: Runnable) { scheduled.removeAll { it.second === action } }
    }

    private fun holder(setting: SpeechModelUnload = SpeechModelUnload.NEVER) =
        SpeechModelKeepAlive(port, { now }, timers) { log += it }.also { it.settingChanged(setting) }

    private fun advance(ms: Long) {
        now += ms
        scheduled.filter { it.first <= now }.forEach { scheduled.remove(it); it.second.run() }
    }

    private fun SpeechModelKeepAlive.take(id: String, transcript: Boolean = true) {
        takeStarted(id); if (transcript) transcriptArrived(id); takeEnded(id)
    }

    @Test fun neverKeepsTheModelWithNoTimerAfterATake() {
        val h = holder()
        h.take("a")
        assertTrue(h.holding)
        assertEquals(Deadline.Forever, h.currentDeadline)
        assertTrue(scheduled.isEmpty())
        assertEquals(1, binds.size)
    }

    @Test fun aSecondTakeReusesTheHoldInsteadOfBindingAgain() {
        val h = holder()
        h.take("a"); h.take("b")
        assertEquals(1, binds.size)
        assertFalse(binds.single().unbound)
    }

    @Test fun immediatelyNeverBinds() {
        val h = holder(SpeechModelUnload.IMMEDIATELY)
        h.take("a")
        assertTrue(binds.isEmpty())
        assertFalse(h.holding)
    }

    @Test fun aTimedChoiceReleasesWhenItsTimeIsUp() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.take("a")
        advance(119_999)
        assertTrue(h.holding)
        advance(1)
        assertFalse(h.holding)
        assertTrue(binds.single().unbound)
    }

    @Test fun theCountdownStartsAtTheTranscriptNotAtTheTakesEnd() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.takeStarted("a"); h.transcriptArrived("a")
        advance(20_000) // polish and insertion
        h.takeEnded("a")
        advance(99_999)
        assertTrue(h.holding)
        advance(1)
        assertFalse("expires 120 s after the transcript", h.holding)
    }

    @Test fun aFailedTakeRestoresTheEarlierHoldsDeadline() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.take("a") // deadline at 120 s
        advance(60_000)
        h.take("b", transcript = false)
        assertEquals(Deadline.At(120_000), h.currentDeadline)
        advance(59_999)
        assertTrue(h.holding)
        advance(1)
        assertFalse(h.holding)
    }

    @Test fun aFailedTakeAfterTheEarlierDeadlinePassedReleasesAtOnce() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.take("a")
        advance(60_000)
        h.takeStarted("b")
        advance(90_000) // the old deadline passes while B runs; B's binding protects it
        assertTrue(h.holding)
        h.takeEnded("b")
        assertFalse(h.holding)
    }

    @Test fun aTakeWithNoTranscriptAndNoEarlierHoldReleases() {
        val h = holder()
        h.take("a", transcript = false)
        assertFalse(h.holding)
        assertTrue(binds.single().unbound)
    }

    @Test fun aStaleOrRepeatedEndChangesNothing() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.take("a") // deadline at 120 s
        advance(60_000)
        h.takeStarted("b")
        h.takeEnded("a") // a late finish from the earlier take
        advance(60_000)
        assertTrue("B is still running, so A's old deadline must not fire", h.holding)
        h.transcriptArrived("b"); h.takeEnded("b"); h.takeEnded("b")
        assertTrue(h.holding)
        assertEquals(Deadline.At(240_000), h.currentDeadline)
    }

    @Test fun theSpeechProcessDyingReleasesAndNothingRebinds() {
        val h = holder()
        h.take("a")
        binds.single().onGone()
        assertFalse(h.holding)
        assertEquals(1, binds.size)
        h.transcriptArrived("a"); h.takeEnded("a")
        assertEquals("a result after the death never binds", 1, binds.size)
    }

    @Test fun aRefusedBindHoldsNothing() {
        refuse = true
        val h = holder()
        h.take("a")
        assertFalse(h.holding)
    }

    @Test fun switchingToImmediatelyReleasesAtOnce() {
        val h = holder()
        h.take("a")
        h.settingChanged(SpeechModelUnload.IMMEDIATELY)
        assertFalse(h.holding)
    }

    @Test fun switchingFromATimedChoiceToNeverCancelsTheTimer() {
        val h = holder(SpeechModelUnload.TWO_MINUTES)
        h.take("a")
        h.settingChanged(SpeechModelUnload.NEVER)
        assertTrue(scheduled.isEmpty())
        advance(3_600_000)
        assertTrue(h.holding)
    }

    @Test fun lowMemoryReleasesTheHoldEvenDuringATake() {
        val h = holder()
        h.take("a")
        h.takeStarted("b")
        h.memoryCritical()
        assertFalse("the take's own binding protects it; the hold goes now", h.holding)
        h.transcriptArrived("b"); h.takeEnded("b")
        assertFalse("the take's end does not bring the hold back", h.holding)
        assertNull(h.currentDeadline)
        assertTrue(binds.single().unbound)
    }

    @Test fun aSettingChangeDuringATakeAppliesToTheEarlierHold() {
        val h = holder()
        h.take("a") // held forever
        h.takeStarted("b")
        h.settingChanged(SpeechModelUnload.TWO_MINUTES)
        assertTrue("no timer while B runs", scheduled.isEmpty())
        h.takeEnded("b") // cancelled, no transcript
        assertEquals(Deadline.At(120_000), h.currentDeadline)
        advance(120_000)
        assertFalse(h.holding)
    }

    @Test fun choosingImmediatelyDuringATakeReleasesTheHoldAtOnce() {
        val h = holder()
        h.take("a")
        h.takeStarted("b")
        h.settingChanged(SpeechModelUnload.IMMEDIATELY)
        assertFalse(h.holding)
        h.transcriptArrived("b"); h.takeEnded("b")
        assertFalse(h.holding)
        assertEquals(1, binds.size)
    }
}
