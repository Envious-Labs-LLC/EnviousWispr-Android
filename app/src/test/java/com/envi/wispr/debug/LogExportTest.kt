package com.envi.wispr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Product Outcome (#378 D6). When this fails, a shared log reads live files that rotated away, claims to be
 * complete, or leaves pins behind, or an idle phone's ZIP holds only main's log (#382). The five writers'
 * acknowledgments are staged as files, exactly what a writer leaves; one process never confirms.
 */
class LogExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theZipReadsOnlyPinnedBytesAndNamesEveryStatus() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        val ids = ArrayDeque(listOf("F", "zipname"))
        val export = LogExport(files, deviceFacts = { "device facts" }, newId = { ids.removeFirst() }, ackWaitMs = 50, lockTimeoutMs = 100)
        // "polish" is busy: another holder keeps its log lock past the bound, so nothing of it can be copied.
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        Thread {
            ProcessLogLock.of(files.lock("polish")).withLock(5_000) { held.countDown(); release.await() }
        }.start()
        held.await()
        files.current("polish").apply { parentFile!!.mkdirs() }.writeText("busy line\n")
        // Four processes acknowledge F; "polish" never does. Each pinned a file longer than its recorded length.
        for (process in LogFiles.PROCESSES - "polish") {
            val dir = files.snapshot("F", process).apply { mkdirs() }
            File(dir, "$process.log").writeText("kept line\nwritten after the pin\n")
            File(dir, LogWriter.LENGTHS).writeText("$process.log 10\n")
            files.ack(process).apply { parentFile!!.mkdirs() }.writeText("F written 0\n")
        }
        val result = try {
            export.build(temp.newFolder("out"))
        } finally {
            release.countDown()
        }
        ZipFile(result.zip).use { zip ->
            val device = zip.getInputStream(zip.getEntry("device.txt")).reader().readText()
            assertTrue(device, device.contains("never complete"))
            assertTrue(device, device.contains("polish: ${LogExport.NOT_CONFIRMED}"))
            assertTrue(device, device.contains("main: written 0"))
            val main = zip.getInputStream(zip.getEntry("logs/main.log")).reader().readText()
            assertEquals("exactly the recorded length", "kept line\n", main)
            assertEquals(null, zip.getEntry("logs/polish.log"))
        }
        assertEquals(LogExport.NOT_CONFIRMED, result.statuses["polish"])
        assertFalse("this export's pins are removed when it ends", File(files.snapshots, "F").exists())
        assertFalse("no partial file is left", result.zip.parentFile!!.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    /**
     * #382, found on the S26 2026-09-27: with the phone idle only main runs, so the other four never confirm, yet
     * their files on disk hold each take's capture, speech and polish lines. They reach the ZIP, copied under each
     * process's own lock, exactly as they stood, and the status says they were copied rather than confirmed.
     * REVERT: skip a NOT_CONFIRMED process in `build` again, and only main.log is in the ZIP.
     */
    @Test fun anIdleProcessesFilesOnDiskReachTheZip() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        val ids = ArrayDeque(listOf("F", "zipname"))
        val export = LogExport(files, deviceFacts = { "" }, newId = { ids.removeFirst() }, ackWaitMs = 50, lockTimeoutMs = 100)
        val main = files.snapshot("F", "main").apply { mkdirs() }
        File(main, "main.log").writeText("main line\n")
        File(main, LogWriter.LENGTHS).writeText("main.log 10\n")
        files.ack("main").apply { parentFile!!.mkdirs() }.writeText("F written 0\n")
        files.logsDir.mkdirs()
        files.current("asr").writeText("asr newest\n")
        files.rotated("asr", 1).writeText("asr older\n")
        val result = export.build(temp.newFolder("out"))
        ZipFile(result.zip).use { zip ->
            assertEquals("asr newest\n", zip.getInputStream(zip.getEntry("logs/asr.log")).reader().readText())
            assertEquals("asr older\n", zip.getInputStream(zip.getEntry("logs/asr.1.log")).reader().readText())
            assertEquals("main line\n", zip.getInputStream(zip.getEntry("logs/main.log")).reader().readText())
            val device = zip.getInputStream(zip.getEntry("device.txt")).reader().readText()
            assertTrue(device, device.contains("asr: ${LogExport.COPIED_UNDER_LOCK}"))
        }
        assertEquals(LogExport.COPIED_UNDER_LOCK, result.statuses["asr"])
        assertEquals("a process with no files has nothing to add", LogExport.COPIED_UNDER_LOCK, result.statuses["vad"])
        assertEquals("the live files are copied, never moved", "asr newest\n", files.current("asr").readText())
        assertFalse("the copies go with this export's pins", File(files.snapshots, "F").exists())
    }

    /**
     * A writer that answers the fence while the export waits for its lock has pinned a whole snapshot under that
     * lock; its pin and its status win over a copy. Staged by acknowledging at the moment the export asks for the
     * lock, after the acknowledgment wait has ended. REVERT: drop the acknowledgment re-read in `copyUnconfirmed`.
     */
    @Test fun aLateAcknowledgmentsPinWinsOverACopy() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        val ids = ArrayDeque(listOf("F", "zipname"))
        files.logsDir.mkdirs()
        files.current("polish").writeText("live line written after the pin\n")
        val export = LogExport(
            files, deviceFacts = { "" }, newId = { ids.removeFirst() }, ackWaitMs = 50, lockTimeoutMs = 100,
            lockFor = { process ->
                if (process == "polish") {
                    val dir = files.snapshot("F", "polish").apply { mkdirs() }
                    File(dir, "polish.log").writeText("pinned line\n")
                    File(dir, LogWriter.LENGTHS).writeText("polish.log 12\n")
                    files.ack("polish").apply { parentFile!!.mkdirs() }.writeText("F written 0\n")
                }
                ProcessLogLock.of(files.lock(process))
            },
        )
        val result = export.build(temp.newFolder("out"))
        assertEquals("written 0", result.statuses["polish"])
        ZipFile(result.zip).use { zip ->
            assertEquals("pinned line\n", zip.getInputStream(zip.getEntry("logs/polish.log")).reader().readText())
        }
    }

    @Test fun startupCancelsTheFenceAndRemovesEveryPin() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        File(files.snapshot("old", "main"), "main.log").apply { parentFile!!.mkdirs() }.writeText("orphan")
        LogExport(files, deviceFacts = { "" }).startupCleanup()
        assertEquals(LogFiles.CANCELLED_FENCE, files.fence.readText())
        assertFalse(File(files.snapshots, "old").exists())
    }
}
