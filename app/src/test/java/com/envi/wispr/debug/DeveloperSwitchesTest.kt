package com.envi.wispr.debug

import com.envi.wispr.settings.DeveloperStored
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Product Outcome (#378 D2). When this fails, turning Detailed log off can show Off while a part of the app
 * can still write, a customer's fresh install starts with logging on, or a quick On then Off leaves logging
 * on. Real flag files and real file locks; the store is a fake so the test controls the order.
 */
class DeveloperSwitchesTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeStore(var stored: DeveloperStored = DeveloperStored(false, null, null)) : DeveloperSwitches.Store {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read() = stored
        override suspend fun setUnlocked() { stored = stored.copy(unlocked = true) }
        override suspend fun setDetailedLog(on: Boolean) {
            // Holds ONLY the first call that finds the gate: a later request passes straight through, which is
            // what lets it overtake the held one when nothing serialises them.
            val held = gate
            gate = null
            held?.await()
            stored = stored.copy(detailedLog = on)
        }
        override suspend fun setKeepRecordings(on: Boolean) { stored = stored.copy(keepRecordings = on) }
    }

    private fun switches(store: FakeStore, debuggable: Boolean = false, timeoutMs: Long = 300): Pair<LogFiles, DeveloperSwitches> {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        files.logsDir.mkdirs()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        return files to DeveloperSwitches(store, files, debuggable, { ProcessLogLock.of(files.lock(it)) }, dispatcher, timeoutMs)
    }

    @Test fun aFreshReleaseInstallStartsWithBothSwitchesOff() = runBlocking {
        val (files, s) = switches(FakeStore(), debuggable = false)
        s.coldStartRepair()
        s.ready.await()
        assertEquals(DeveloperSwitches.Switch.Off, s.state.value.detailedLog)
        assertEquals(DeveloperSwitches.Switch.Off, s.state.value.keepRecordings)
        assertFalse(s.state.value.unlocked)
        assertFalse(files.detailedLogFlag.exists())
        assertFalse(files.keepRecordingsFlag.exists())
    }

    @Test fun aDebugBuildStartsWithBothSwitchesOn() = runBlocking {
        val (files, s) = switches(FakeStore(), debuggable = true)
        s.coldStartRepair()
        s.ready.await()
        assertEquals(DeveloperSwitches.Switch.On, s.state.value.detailedLog)
        assertTrue(files.detailedLogFlag.exists())
        assertTrue(files.keepRecordingsFlag.exists())
    }

    /** REVERT: delete the flag outside the five locks in `offBarrier`, and Off is reported while a writer holds its lock. */
    @Test fun offIsNeverReportedWhileAWriterHoldsItsLock() = runBlocking {
        val store = FakeStore()
        val (files, s) = switches(store)
        s.coldStartRepair()
        assertEquals(DeveloperSwitches.Switch.On, s.requestDetailedLog(true).await())
        // A writer mid-batch in another thread of "audio" holds its lock past the barrier's bound.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread {
            ProcessLogLock.of(files.lock("audio")).withLock(5_000) { held.countDown(); release.await(5, TimeUnit.SECONDS) }
        }.start()
        held.await()
        val result = s.requestDetailedLog(false).await()
        assertTrue("never a settled Off while a lock is held: $result", result is DeveloperSwitches.Switch.Error)
        assertTrue("the flag is still there, so writers keep their state", files.detailedLogFlag.exists())
        release.countDown()
        assertEquals(DeveloperSwitches.Switch.Off, s.requestDetailedLog(false).await())
        assertFalse(files.detailedLogFlag.exists())
    }

    /**
     * REVERT: remove `serial.withLock` from `requestDetailedLog`. The On request then suspends in the store, the
     * Off request runs to completion in that gap, and On resumes to recreate the flag after Off reported Off.
     */
    @Test fun aDelayedOnCannotRecreateTheFlagAfterALaterOff() = runBlocking {
        val store = FakeStore()
        val (files, s) = switches(store)
        s.coldStartRepair()
        s.ready.await()
        val gate = CompletableDeferred<Unit>()
        store.gate = gate
        val on = s.requestDetailedLog(true)
        val off = s.requestDetailedLog(false)
        // Off must not be able to finish while the earlier On is still inside its request.
        val offFinishedEarly = kotlinx.coroutines.withTimeoutOrNull(300) { off.await() }
        assertEquals("Off waits behind the earlier On", null, offFinishedEarly)
        gate.complete(Unit)
        on.await()
        off.await()
        assertEquals(DeveloperSwitches.Switch.Off, s.state.value.detailedLog)
        assertFalse(files.detailedLogFlag.exists())
        assertEquals(false, store.stored.detailedLog)
    }

    @Test fun keepRecordingsFollowsTheLastRequest() = runBlocking {
        val store = FakeStore()
        val (files, s) = switches(store)
        s.coldStartRepair()
        val on = s.requestKeepRecordings(true)
        val off = s.requestKeepRecordings(false)
        on.await(); off.await()
        assertEquals(DeveloperSwitches.Switch.Off, s.state.value.keepRecordings)
        assertFalse(files.keepRecordingsFlag.exists())
    }
}
