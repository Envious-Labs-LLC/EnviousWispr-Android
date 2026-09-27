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
 * complete, or leaves pins behind. The five writers' acknowledgments are staged as files, exactly what a
 * writer leaves; one process never confirms.
 */
class LogExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theZipReadsOnlyPinnedBytesAndNamesEveryStatus() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        val ids = ArrayDeque(listOf("F", "zipname"))
        val export = LogExport(files, deviceFacts = { "device facts" }, newId = { ids.removeFirst() }, ackWaitMs = 50)
        // Four processes acknowledge F; "polish" never does. Each pinned a file longer than its recorded length.
        for (process in LogFiles.PROCESSES - "polish") {
            val dir = files.snapshot("F", process).apply { mkdirs() }
            File(dir, "$process.log").writeText("kept line\nwritten after the pin\n")
            File(dir, LogWriter.LENGTHS).writeText("$process.log 10\n")
            files.ack(process).apply { parentFile!!.mkdirs() }.writeText("F written 0\n")
        }
        val result = export.build(temp.newFolder("out"))
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

    @Test fun startupCancelsTheFenceAndRemovesEveryPin() {
        val files = LogFiles(temp.newFolder(), temp.newFolder())
        File(files.snapshot("old", "main"), "main.log").apply { parentFile!!.mkdirs() }.writeText("orphan")
        LogExport(files, deviceFacts = { "" }).startupCleanup()
        assertEquals(LogFiles.CANCELLED_FENCE, files.fence.readText())
        assertFalse(File(files.snapshots, "old").exists())
    }
}
