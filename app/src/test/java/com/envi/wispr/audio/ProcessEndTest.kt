package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drift Guard (#213), not product coverage: the capture process's end writes its note first, never waits
 * on the note past its bound, and runs once. [ProcessEnd.end] never returns; the test's `park` throws.
 */
class ProcessEndTest {

    private class Parked : RuntimeException()

    // Row 13. REVERT: kill before the note.
    @Test
    fun theNoteStartsBeforeTheKill() {
        val order: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val end = ProcessEnd(
            writeNote = { order += "note" },
            kill = { order += "kill" },
            noteBoundMs = 5_000L,
            park = { throw Parked() },
        )
        runCatching { end.end() }
        assertEquals(listOf("note", "kill"), order.toList())
    }

    // Row 14. REVERT: an unbounded join.
    @Test
    fun aNoteThatNeverFinishesDoesNotHoldTheKill() {
        val held = CountDownLatch(1)
        val killed = CountDownLatch(1)
        val end = ProcessEnd(
            writeNote = { held.await(10, TimeUnit.SECONDS) },
            kill = { killed.countDown() },
            noteBoundMs = 50L,
            park = { throw Parked() },
        )
        val runner = Executors.newSingleThreadExecutor()
        try {
            runner.submit { runCatching { end.end() } }
            assertTrue("the kill ran while the note was still held", killed.await(5, TimeUnit.SECONDS))
        } finally {
            held.countDown()
            runner.shutdownNow()
        }
    }

    // Row 15.
    @Test
    fun twoEndsWriteOneNoteAndKillOnce() {
        val notes = AtomicInteger(0)
        val kills = AtomicInteger(0)
        val end = ProcessEnd(
            writeNote = { notes.incrementAndGet() },
            kill = { kills.incrementAndGet() },
            noteBoundMs = 5_000L,
            park = { throw Parked() },
        )
        runCatching { end.end() }
        runCatching { end.end() }
        assertEquals(1, notes.get())
        assertEquals(1, kills.get())
    }
}
