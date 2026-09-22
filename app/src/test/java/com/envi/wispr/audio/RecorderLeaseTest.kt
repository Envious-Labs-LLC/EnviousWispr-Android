package com.envi.wispr.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome (#115): when this fails, a replacement audio Service in the same process opens a
 * second recorder while an abandoned capture thread still holds the first, and the new take records
 * silence or fails to start with no sentence. The lease is a pure owner here; the service's use of it is
 * pinned by shape (acquired before the recorder is created, released after it is released, refused
 * with the start-failure ending).
 */
class RecorderLeaseTest {
    @Test
    fun theSecondAcquireIsRefusedUntilTheFirstIsReleased() {
        val lease = RecorderLease()
        assertTrue(lease.acquire())
        assertFalse("a second recorder in the process is refused", lease.acquire())
        assertTrue(lease.isHeld)
        lease.release()
        assertFalse(lease.isHeld)
        assertTrue("released, the next start may hold it", lease.acquire())
    }

    @Test
    fun theServiceAcquiresBeforeTheRecorderAndReleasesAfterIt() {
        val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
        val start = service.substringAfter("private fun startRecording(").substringBefore("\n    private fun ")
        val acquire = start.indexOf("if (!RecorderLease.PROCESS.acquire())")
        val create = start.indexOf("record = AudioRecord(")
        assertTrue("the lease is taken before the recorder is created", acquire in 0 until create)
        val refusal = start.substring(acquire, create)
        assertTrue("a refused lease ends the take with the start-failure ending", refusal.contains("publishStartRefused(takeId, lastStartFailure)") && refusal.contains("return false"))
        val close = service.substringAfter("private fun closeResources(record: AudioRecord?, output: FileOutputStream?)").substringBefore("\n    }\n")
        assertTrue("the lease is released after the recorder is", close.indexOf("RecorderLease.PROCESS.release()") > close.indexOf("record?.release()"))
        assertTrue("and only there", Regex("RecorderLease\\.PROCESS\\.release\\(\\)").findAll(service).count() == 1)
    }
}
