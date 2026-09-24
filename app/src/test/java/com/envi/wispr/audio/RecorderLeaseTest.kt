package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drift Guard (#115, #213): the process's one recorder. The lease is a pure state machine here: a capture
 * still RECORDING is never condemned, a capture told to end or whose release failed is condemned exactly
 * once and then nobody acquires again, and a release and a condemnation of the same holder cannot both
 * win. The service's use of it is pinned by shape, because the service is an Android `Service`.
 */
class RecorderLeaseTest {

    private val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val start = service.substringAfter("private fun startRecording(").substringBefore("\n    private fun ")

    @Test
    fun theSecondAcquireIsRefusedUntilTheFirstIsReleased() {
        val lease = RecorderLease()
        assertTrue(lease.acquire(1L))
        assertFalse("a second recorder in the process is refused", lease.acquire(2L))
        assertTrue(lease.isHeld)
        assertTrue(lease.release(1L))
        assertFalse(lease.isHeld)
        assertTrue("released, the next start may hold it", lease.acquire(2L))
    }

    // Row 1. REVERT: condemn without the RECORDING check.
    @Test
    fun aCaptureStillRecordingIsNeverCondemned() {
        val lease = RecorderLease()
        lease.acquire(1L)
        assertFalse(lease.condemnAbandoned())
        assertEquals(RecorderLease.Holder(1L, RecorderLease.State.RECORDING), lease.snapshot)
    }

    // Row 2.
    @Test
    fun aCaptureToldToEndIsCondemnedOnceAndNobodyAcquiresAgain() {
        val lease = RecorderLease()
        lease.acquire(1L)
        lease.markEnding(1L)
        assertTrue(lease.condemnAbandoned())
        assertFalse("condemned once", lease.condemnAbandoned())
        assertFalse("the process can never hold a recorder again", lease.acquire(2L))
        assertFalse("and the stuck capture cannot give it back", lease.release(1L))
    }

    // Row 3.
    @Test
    fun aCaptureWhoseReleaseFailedIsCondemned() {
        val lease = RecorderLease()
        lease.acquire(1L)
        lease.markEnding(1L)
        lease.releaseFailed(1L)
        assertEquals(RecorderLease.State.RELEASE_FAILED, lease.snapshot?.state)
        assertTrue(lease.condemnAbandoned())
    }

    // Row 4. REVERT: a plain set in release.
    @Test
    fun aReleaseThatWinsLeavesNothingToCondemnAndTheNextStartAcquires() {
        val lease = RecorderLease()
        lease.acquire(1L)
        lease.markEnding(1L)
        assertTrue(lease.release(1L))
        assertFalse(lease.condemnAbandoned())
        assertTrue(lease.acquire(2L))
        assertFalse("the newer capture is recording, never condemnable", lease.condemnAbandoned())
    }

    // Row 5.
    @Test
    fun aReleaseByATokenThatDoesNotHoldTheLeaseChangesNothing() {
        val lease = RecorderLease()
        lease.acquire(2L)
        assertFalse(lease.release(1L))
        assertEquals(RecorderLease.Holder(2L, RecorderLease.State.RECORDING), lease.snapshot)
    }

    // Row 6. REVERT: markEnding without the token check.
    @Test
    fun anOlderCaptureCannotMarkANewerOneAsEnding() {
        val lease = RecorderLease()
        lease.acquire(1L)
        lease.release(1L)
        lease.acquire(2L)
        lease.markEnding(1L)
        lease.releaseFailed(1L)
        assertEquals(RecorderLease.Holder(2L, RecorderLease.State.RECORDING), lease.snapshot)
        assertFalse(lease.condemnAbandoned())
    }

    // Row 7: a release and a condemnation of the same ENDING holder, raced; exactly one wins every time.
    @Test
    fun aReleaseAndACondemnationNeverBothWin() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(1000) { round ->
                val lease = RecorderLease()
                val token = round + 1L
                lease.acquire(token)
                lease.markEnding(token)
                val barrier = CyclicBarrier(2)
                val released = pool.submit<Boolean> { barrier.await(5, TimeUnit.SECONDS); lease.release(token) }
                val condemned = pool.submit<Boolean> { barrier.await(5, TimeUnit.SECONDS); lease.condemnAbandoned() }
                val r = released.get(5, TimeUnit.SECONDS)
                val c = condemned.get(5, TimeUnit.SECONDS)
                assertTrue("round $round: exactly one wins (released=$r condemned=$c)", r xor c)
                if (r) assertNull(lease.snapshot) else assertEquals(RecorderLease.State.CONDEMNED, lease.snapshot?.state)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // Row 8. REVERT: acquire moved after the hand-over.
    @Test
    fun theTokenIsMintedThenTheLeaseTakenBeforeAnyRouteWork() {
        val mint = start.indexOf("val token = nextCaptureToken()")
        val acquire = start.indexOf("if (!RecorderLease.PROCESS.acquire(token))")
        assertTrue("the token is minted before the acquire", mint in 0 until acquire)
        listOf("TakeRoute.newHold(", "warmHoldOwner.handOver()", "TakeRoute.resolve(", "record = AudioRecord(").forEach { later ->
            assertTrue("$later comes after the acquire", start.indexOf(later) > acquire)
        }
    }

    // Row 9. REVERT: remove the condemn branch.
    @Test
    fun aRefusedProductionStartCondemnsAnAbandonedHolderAndEndsTheProcess() {
        val acquire = start.indexOf("if (!RecorderLease.PROCESS.acquire(token))")
        val refusal = start.substring(acquire, start.indexOf("var routeHold: RouteHold? = null"))
        val condemn = refusal.indexOf("if (mayRecover && RecorderLease.PROCESS.condemnAbandoned())")
        assertTrue("only the production start may condemn", condemn >= 0)
        assertTrue("and the condemnation ends the process", refusal.indexOf("endCaptureProcess()") > condemn)
        assertTrue("otherwise refused as before", refusal.contains("publishStartRefused(takeId, lastStartFailure)") && refusal.contains("return false"))
    }

    // Row 10. REVERT: move TakeRoute.resolve outside the try, or a return after the acquire that skips failSetup.
    @Test
    fun everyReturnBeforeTheThreadOwnsTheRecorderReleasesTheLease() {
        val afterAcquire = start.substring(start.indexOf("var routeHold: RouteHold? = null"))
        val tryAt = afterAcquire.indexOf("try {")
        assertTrue("the cleanup-owning try opens right after the nullable declarations", tryAt in 0..200)
        listOf("TakeRoute.newHold(", "warmHoldOwner.handOver()", "TakeRoute.resolve(", "AudioRecord.getMinBufferSize(").forEach {
            assertTrue("$it is inside the try", afterAcquire.indexOf(it) > tryAt)
        }
        assertTrue(
            "the no-device refusal keeps its own failure code",
            afterAcquire.contains("failSetup(START_FAILURE_NO_INPUT_DEVICE, threadStarted, record, output, routeHold, takeId, token)"),
        )
        assertEquals(
            "the buffer-size refusal and both catches pass the token",
            3,
            Regex("""failSetup\(START_FAILURE_OTHER, threadStarted, record, output, routeHold, takeId, token\)""").findAll(afterAcquire).count(),
        )
        val threadStart = afterAcquire.substring(afterAcquire.indexOf("thread.start()"))
        assertTrue("the thread-start failure releases through the session's token", threadStart.contains("closeResources(newSession, keepRoute = false)"))
        val beforeTry = afterAcquire.substring(0, tryAt)
        assertFalse("no return between the acquire and the try", beforeTry.contains("return"))
    }

    // Row 11.
    @Test
    fun theRecorderReleaseReleasesOrMarksTheLeaseByToken() {
        val close = service.substringAfter("private fun closeResources(record: AudioRecord?, output: FileOutputStream?, token: Long)").substringBefore("\n    }\n")
        assertTrue("released after the recorder is", close.indexOf("RecorderLease.PROCESS.release(token)") > close.indexOf("record.release()"))
        assertTrue(close.contains("if (released) {\n            RecorderLease.PROCESS.release(token)"))
        assertTrue("a failed release is marked so a later start can recover", close.contains("RecorderLease.PROCESS.releaseFailed(token)"))
        assertEquals("and only there", 1, Regex("""RecorderLease\.PROCESS\.release\(""").findAll(service).count())
        assertTrue(
            "the session path passes the session's token",
            service.contains("closeResources(active.record, active.output, active.token)"),
        )
    }

    // Row 12. REVERT: remove markEnding from claimEnding.
    @Test
    fun theWinningClaimMarksTheLeaseAsEnding() {
        val claim = service.substringAfter("private fun claimEnding(active: CaptureSession, reason: Int): Boolean {").substringBefore("\n    }\n")
        val won = claim.indexOf("if (!active.endingClaim.claim(reason)) return false")
        assertTrue(won >= 0 && claim.indexOf("RecorderLease.PROCESS.markEnding(active.token)") > won)
    }

    // Row 16. REVERT: let a legacy transaction pass mayRecover = true, or redirect an older start to startTake.
    @Test
    fun onlyTheProductionStartMayRecover() {
        // #361: the transactions live in the adapters and call the service's operations.
        val adapters = File("src/main/java/com/envi/wispr/audio/CaptureBinderAdapters.kt").readText()
        val legacy = adapters.substringAfter("val legacy: IBinder = object : IAudioCaptureService.Stub() {").substringBefore("override fun getTakePeakAmplitude")
        val older = legacy.substringBefore("override fun startCaptureForTake(")
        val olderStarts = Regex("""ops\.(\w+)\(([^\n]*)\)""").findAll(older).toList()
        assertEquals("four older start transactions", 4, olderStarts.size)
        assertTrue("each calls the refusal-only legacy start", olderStarts.all { it.groupValues[1] == "startLegacy" })
        val legacyStart = service.substringAfter("override fun startLegacy(").substringBefore("\n\n")
        assertTrue("which is startRecording without recovery", legacyStart.contains("startRecording(") && !legacyStart.contains("mayRecover"))
        // #220: both interfaces' startCaptureForTake call ONE helper, and only it may recover.
        val takeStarts = Regex("""override fun startCaptureForTake\([^\n]*\n\s*ops\.(\w+)\(""").findAll(adapters).map { it.groupValues[1] }.toList()
        assertEquals("both startCaptureForTake transactions delegate to startTake", listOf("startTake", "startTake"), takeStarts)
        assertTrue("which is the service's startTake", service.contains("this@AudioCaptureService.startTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)"))
        assertEquals("exactly one call in the service may recover", 1, Regex("mayRecover = true").findAll(service).count())
        assertTrue("and it is startTake's", service.substringAfter("private fun startTake(").substringBefore("\n\n").contains("mayRecover = true"))
        assertTrue("the default is refusal-only", start.contains("mayRecover: Boolean = false"))
    }

    // Row 18. REVERT: substitute another defect, or drop the note writer.
    @Test
    fun theProcessEndWritesTheCaptureReleaseDefectFirst() {
        val end = service.substringAfter("private val processEnd = ProcessEnd(").substringBefore("\n    )\n")
        assertTrue(end.contains("writeNote = { Telemetry.recordPendingDefect(applicationContext, AppDefect.CaptureReleaseWedged,"))
        assertTrue(end.contains("kill = { android.os.Process.killProcess(android.os.Process.myPid()) }"))
        assertTrue(service.contains("private fun endCaptureProcess(): Nothing = processEnd.end()"))
    }
}
