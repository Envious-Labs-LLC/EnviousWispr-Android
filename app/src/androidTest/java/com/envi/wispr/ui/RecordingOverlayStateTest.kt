package com.envi.wispr.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.audio.SpectrumAnalyzer
import com.envi.wispr.shortcuts.RecordingOverlayState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome. When this fails the recorder either stops reporting what the session is doing, or
 * stays on screen over the user's own app after the take is over, with nothing left running to
 * dismiss it.
 */
@RunWith(AndroidJUnit4::class)
class RecordingOverlayStateTest {

    private val received = CopyOnWriteArrayList<RecordingOverlayState.Snapshot>()
    private val awaited =
        AtomicReference<Pair<(RecordingOverlayState.Snapshot) -> Boolean, CountDownLatch>?>(null)
    private var listener: RecordingOverlayState.Listener? = null

    /**
     * Arm the wait BEFORE the thing that causes the delivery, then do it.
     *
     * Arming afterwards is a race the test loses about as often as the phone is busy: the delivery
     * lands first, nothing is listening for it yet, and the wait then times out against a state that
     * was already correct.
     */
    private fun expect(
        what: String,
        matches: (RecordingOverlayState.Snapshot) -> Boolean,
        action: () -> Unit,
    ) {
        val latch = CountDownLatch(1)
        awaited.set(matches to latch)
        action()
        assertTrue(
            "Timed out waiting for $what. Received: $received",
            latch.await(5, TimeUnit.SECONDS),
        )
        awaited.set(null)
    }

    private fun attachAndWaitForFirstDelivery() {
        val observer = RecordingOverlayState.Listener { snapshot ->
            received += snapshot
            awaited.get()?.let { (matches, latch) -> if (matches(snapshot)) latch.countDown() }
        }
        listener = observer
        expect("the first delivery after attaching", { true }) {
            RecordingOverlayState.attach(observer)
        }
    }

    /** Drain the main thread so a delivery that WAS posted cannot hide behind the assertion. */
    private fun drainMainThread() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun cleanup() {
        awaited.set(null)
        listener?.let(RecordingOverlayState::detach)
        RecordingOverlayState.hide()
    }

    @Test
    fun theRecorderIsToldWhenItAppearsHowLongItHasRunAndWhenItGoes() {
        attachAndWaitForFirstDelivery()

        expect("the recorder to appear", { it.visible && it.elapsedSeconds == 0 }) {
            RecordingOverlayState.show()
        }
        expect("seven seconds elapsed", { it.visible && it.elapsedSeconds == 7 }) {
            RecordingOverlayState.updateElapsed(7)
        }
        expect("the recorder to go", { !it.visible }) {
            RecordingOverlayState.hide()
        }
    }

    @Test
    fun aMicrophonePicturePreparedBeforeAStopCannotBringTheRecorderBack() {
        // The defect this exists for: the picture is published about thirty times a second, so a stop
        // lands in the middle of one. If a picture decided before the stop can be committed after it,
        // the recorder reappears over whatever app the user is now in, with the take already over and
        // nothing left running to hide it again.
        attachAndWaitForFirstDelivery()

        expect("the recorder to appear", { it.visible }) { RecordingOverlayState.show() }
        val serial = RecordingOverlayState.snapshots.value.takeSerial

        val racers = 8
        val ready = CountDownLatch(racers)
        val go = CountDownLatch(1)
        val threads = (1..racers).map { index ->
            Thread({
                ready.countDown()
                go.await()
                repeat(60) { RecordingOverlayState.updateBands(serial, FloatArray(SpectrumAnalyzer.BAND_COUNT) { band -> ((index + it + band) % 32) / 32f }) }
            }, "PictureRacer$index")
        }
        threads.forEach { it.start() }
        assertTrue("racers did not start", ready.await(5, TimeUnit.SECONDS))

        expect("the recorder to go", { !it.visible }) {
            go.countDown()
            RecordingOverlayState.hide()
        }
        threads.forEach { it.join(5_000) }
        threads.forEach { assertFalse("a racer never finished", it.isAlive) }

        drainMainThread()
        assertFalse(
            "a picture published across the stop reopened the recorder. Received: $received",
            received.last().visible,
        )

        // And one arriving strictly after the stop must be refused as well.
        RecordingOverlayState.updateBands(serial, FloatArray(SpectrumAnalyzer.BAND_COUNT) { 0.5f })
        drainMainThread()
        assertFalse(
            "a picture published after the stop reopened the recorder. Received: $received",
            received.last().visible,
        )
    }

    @Test
    fun aPictureFromThePreviousTakeCannotPaintTheNextOne() {
        // A reading blocked in the audio process during take N can return during take N+1. It carries
        // N's serial, and the snapshot's serial is N+1's, so the locked change refuses it whole.
        attachAndWaitForFirstDelivery()
        expect("the first take to appear", { it.visible }) { RecordingOverlayState.show() }
        val stale = RecordingOverlayState.snapshots.value.takeSerial
        expect("the first take to end", { !it.visible }) { RecordingOverlayState.hide() }
        expect("the second take to appear", { it.visible }) { RecordingOverlayState.show() }
        val before = RecordingOverlayState.snapshots.value
        assertTrue("show() must stamp a new serial", before.takeSerial != stale)

        RecordingOverlayState.updateBands(stale, FloatArray(SpectrumAnalyzer.BAND_COUNT) { 1f })
        drainMainThread()
        val after = RecordingOverlayState.snapshots.value
        assertTrue("a stale picture must change nothing", after === before)

        RecordingOverlayState.updateBands(before.takeSerial, FloatArray(SpectrumAnalyzer.BAND_COUNT) { 1f })
        drainMainThread()
        assertTrue("a current picture lands", RecordingOverlayState.snapshots.value.bands.all { it == 1f })
        RecordingOverlayState.hide()
    }
}
