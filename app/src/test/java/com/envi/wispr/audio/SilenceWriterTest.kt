package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The warm hold's write loop (#241), driven through the same `SilenceWriter` the silent track runs. Every write
 * sequence is finite, so a regression fails instead of spinning.
 */
class SilenceWriterTest {

    /** Hands back [results] in order; a `null` entry throws, as a dead track's write can. */
    private fun writes(vararg results: Int?): Pair<() -> Int, () -> Int> {
        var i = 0
        val write = {
            // An Error, not an Exception: the writer catches exceptions as write failures, and an extra write must
            // fail the row, never count as the failure it expects (code review round 1).
            if (i >= results.size) throw AssertionError("the loop wrote past its script")
            results[i++] ?: throw IllegalStateException("dead track")
        }
        return write to { i }
    }

    /** Row 2a: zero and a full write are progress; a negative write fails once. MUTATION: drop the negative-result signal. */
    @Test fun aNegativeWriteFailsOnceAfterZeroAndFullWrites() {
        var failures = 0
        val (write, count) = writes(0, 3200, -1)
        SilenceWriter(write) { failures++ }.run()
        assertEquals("zero and a full write kept it running; the negative one ended it", 3, count())
        assertEquals(1, failures)
    }

    /** Row 2b: a thrown write fails once. MUTATION: drop the exception handler. */
    @Test fun aThrownWriteFailsOnce() {
        var failures = 0
        val (write, _) = writes(3200, null)
        SilenceWriter(write) { failures++ }.run()
        assertEquals(1, failures)
    }

    /** Row 4a: a stop that wins silences the error its own track stop causes. MUTATION: report a failure from STOPPED. */
    @Test fun aStopThatWinsSilencesItsOwnWriteError() {
        var failures = 0
        lateinit var writer: SilenceWriter
        var calls = 0
        writer = SilenceWriter({ calls++; writer.stop(); -3 }) { failures++ }
        writer.run()
        assertEquals("the write ran", 1, calls)
        assertEquals("a stop-induced error is not a failure", 0, failures)
    }

    /**
     * Row 4b: a failure ends the loop and is reported once; the loop never writes on after it. MUTATION: keep
     * writing and reporting after a failure (the script's second failure is then reported too).
     */
    @Test fun aFailureIsReportedOnce() {
        var failures = 0
        val (write, count) = writes(-1, -1)
        SilenceWriter(write) { failures++ }.run()
        assertEquals("the loop stopped at the first failure", 1, count())
        assertEquals(1, failures)
    }

    private fun source(path: String) = File("src/main/java/com/envi/wispr/$path").readText()

    /** Row 4c: the silent track runs this writer and has no write loop of its own. MUTATION: restore a private loop. */
    @Test fun theSilentTrackRunsTheWriter() {
        val owner = source("audio/WarmHoldOwner.kt")
        val silence = owner.substringAfter("private class AudioTrackSilence")
        // Since #257 the thread lives in SilenceWriterThread, which runs the one SilenceWriter and says when it exited.
        assertTrue(silence.contains("SilenceWriterThread(write = { built.write(zeros, 0, zeros.size) }, onFailed = onFailed)"))
        assertTrue(silence.contains("silence.start()"))
        val thread = source("audio/SilenceWriterWatch.kt").substringAfter("internal class SilenceWriterThread").substringBefore("\n}\n")
        assertTrue(thread.contains("private val writer = SilenceWriter(write, onFailed)"))
        assertTrue(thread.contains("Thread({ runToExit() }, \"WarmHoldSilence\")"))
        assertTrue("the exit signal follows the loop", thread.substringAfter("private fun runToExit()").let { it.indexOf("writer.run()") in 0 until it.indexOf("exit.countDown()") })
        assertTrue("no second write loop", !Regex("while \\(").containsMatchIn(silence))
        val stop = silence.substringAfter("override fun stop()")
        assertTrue("the writer stops before the platform track", stop.indexOf("writer?.stop()") in 0 until stop.indexOf("t.stop()"))
    }

    /**
     * Row 5: a late failure can never stop a live take, because the service stops only when no take is open.
     * MUTATION: remove the `session == null` guard from the hold owner's `onIdle`.
     */
    @Test fun theServiceStopsForAHoldOnlyWhenNoTakeIsOpen() {
        assertTrue(source("audio/AudioCaptureService.kt").contains("onIdle = { if (session == null) stopSelf() },"))
    }
}
