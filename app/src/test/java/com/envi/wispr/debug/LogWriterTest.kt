package com.envi.wispr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Product Outcome (#378). When this fails, the founder's pulled log is missing a take's lines or words, or a
 * phone with Detailed log off writes or builds a line anyway. Drives the real [LogWriter] against a temporary
 * `filesDir`, one batch at a time on the test thread; the flag is a real file, the lock a real file lock.
 */
class LogWriterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun setup(capacity: Int = LogWriter.DEFAULT_CAPACITY): Pair<LogFiles, LogWriter> {
        val files = LogFiles(temp.newFolder("files"), temp.newFolder("cache"))
        files.flagsDir.mkdirs()
        files.logsDir.mkdirs()
        val writer = LogWriter(
            files = files,
            process = "main",
            lock = ProcessLogLock.of(files.lock("main")),
            wallClock = { 0L },
            capacity = capacity,
        )
        return files to writer
    }

    private fun entry(take: String?, text: () -> String) = LogWriter.Entry(0L, 42L, 'I', "Tag", take, text)

    /** REVERT: drop the flag check in `writeEntries`, and the file appears and the lambda runs. */
    @Test fun offWritesNothingAndBuildsNoString() {
        val (files, writer) = setup()
        var built = 0
        writer.process(listOf(entry("t1") { built++; "SENTINEL words" }))
        assertEquals("no text is built while Detailed log is off", 0, built)
        assertFalse("no file is written while Detailed log is off", files.current("main").exists())
    }

    @Test fun onWritesTheLineWithItsTakeIdAsAField() {
        val (files, writer) = setup()
        files.detailedLogFlag.createNewFile()
        writer.process(listOf(entry("7837f3e0") { "SENTINEL words" }, entry(null) { "no take here" }))
        val text = files.current("main").readText()
        assertTrue(text, text.contains("b=42 [main] I [Tag] take=7837f3e0 SENTINEL words"))
        assertTrue(text, text.contains("take=- no take here"))
    }

    /** REVERT: make `rotate` truncate instead of rename, and the older file disappears. */
    @Test fun rotationRenamesAndNeverRewrites() {
        val (files, writer) = setup()
        files.detailedLogFlag.createNewFile()
        val big = "x".repeat(700_000)
        repeat(5) { writer.process(listOf(entry("t") { big })) }
        assertTrue("the full file was renamed to .1", files.rotated("main", 1).exists())
        assertTrue("the current file stays under the cap", files.current("main").length() <= LogFiles.MAX_FILE_BYTES)
        assertTrue("every kept byte is a whole line", files.rotated("main", 1).readText().endsWith("\n"))
    }

    @Test fun aFenceAcknowledgesWithAPinnedSnapshotThatOutlivesRotation() {
        val (files, writer) = setup()
        files.detailedLogFlag.createNewFile()
        writer.process(listOf(entry("t") { "before the fence" }))
        files.fence.writeText("F1")
        writer.process(listOf(LogWriter.Signal.Fence))
        assertEquals("F1 written 0", files.ack("main").readText().trim())
        val pinned = File(files.snapshot("F1", "main"), "main.log")
        val lengths = File(files.snapshot("F1", "main"), LogWriter.LENGTHS).readText()
        assertTrue(lengths, lengths.startsWith("main.log "))
        // Lines written after the pin, and the live file going away, do not change the pin.
        writer.process(listOf(entry("t") { "after the fence" }))
        files.current("main").delete()
        assertTrue(pinned.readText().contains("before the fence"))
        assertFalse(pinned.readText().contains("after the fence"))
    }

    @Test fun aFenceUnderOffIsReportedAsDiscarded() {
        val (files, writer) = setup()
        writer.process(listOf(entry("t") { "discarded" }))
        files.fence.writeText("F2")
        writer.process(listOf(LogWriter.Signal.Fence))
        assertEquals("F2 discarded-by-Off 0", files.ack("main").readText().trim())
    }

    @Test fun anOverflowIsWrittenAndReportedOnTheFence() {
        val (files, writer) = setup(capacity = 2)
        files.detailedLogFlag.createNewFile()
        repeat(3) { i -> writer.offer(entry("t") { "line $i" }) }
        // Drain what the queue kept, the way the writer thread would.
        val batch = ArrayList<Any>()
        val field = LogWriter::class.java.getDeclaredField("queue").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(writer) as java.util.concurrent.LinkedBlockingDeque<Any>).drainTo(batch)
        writer.process(batch)
        val text = files.current("main").readText()
        assertTrue(text, text.contains("dropped=1 queued lines"))
        assertFalse("the oldest line is the one dropped", text.contains("line 0"))
        files.fence.writeText("F3")
        writer.process(listOf(LogWriter.Signal.Fence))
        assertEquals("F3 dropped 1", files.ack("main").readText().trim())
    }

    @Test fun theCancellationFenceIsNeverAcknowledged() {
        val (files, writer) = setup()
        files.fence.writeText(LogFiles.CANCELLED_FENCE)
        writer.process(listOf(LogWriter.Signal.Fence))
        assertFalse(files.ack("main").exists())
    }
}
