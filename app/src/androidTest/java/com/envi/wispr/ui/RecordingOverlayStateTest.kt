package com.envi.wispr.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.shortcuts.RecordingOverlayState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingOverlayStateTest {
    private var listener: RecordingOverlayState.Listener? = null

    @After
    fun cleanup() {
        listener?.let(RecordingOverlayState::detach)
        RecordingOverlayState.hide()
    }

    @Test
    fun recordingStateShowsUpdatesAndHidesTrustedOverlayModel() {
        val received = CopyOnWriteArrayList<RecordingOverlayState.Snapshot>()
        val latch = CountDownLatch(4)
        val observer = RecordingOverlayState.Listener { snapshot ->
            received += snapshot
            latch.countDown()
        }
        listener = observer

        RecordingOverlayState.attach(observer)
        RecordingOverlayState.show()
        RecordingOverlayState.updateElapsed(7)
        RecordingOverlayState.hide()

        assertTrue("Overlay state callbacks timed out", latch.await(2, TimeUnit.SECONDS))
        assertTrue(received.any { it.visible && it.elapsedSeconds == 0 })
        assertTrue(received.any { it.visible && it.elapsedSeconds == 7 })
        assertTrue(received.last().visible.not())
    }
}
