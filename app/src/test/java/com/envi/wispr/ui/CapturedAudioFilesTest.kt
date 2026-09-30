package com.envi.wispr.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Product Outcome (#358, carrying #253): a take's recording is deleted once its ending no longer needs it, on the
 * process worker rather than the caller's time, and a delete that fails says so. When this fails, the user's
 * recording stays in app storage after the take, or a teardown loses the delete. Each row names its mutation.
 */
class CapturedAudioFilesTest {
    private val queued = mutableListOf<Runnable>()
    private val warnings = mutableListOf<String>()
    private val kept = mutableListOf<String>()
    private var archiveThrows = false
    private val files = CapturedAudioFiles(execute = { queued += it }, warn = { warnings += it }, archive = { file ->
        if (archiveThrows) throw java.io.IOException("disk full")
        if (file.isFile) kept += file.readText()
    })
    private val dir = Files.createTempDirectory("captured").toFile()

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** Row 1: the delete is queued, not run on the caller's time, and removes the file when it runs. MUTATION m1: the delete runs inline. */
    @Test fun aDeleteIsQueuedAndRemovesTheFileWhenItRuns() {
        val audio = File(dir, "take.pcm").apply { writeBytes(ByteArray(32_000)) }
        files.delete(audio.path)
        assertTrue("still on disk until the worker runs", audio.exists())
        assertEquals(1, queued.size)
        queued.single().run()
        assertFalse(audio.exists())
        assertTrue(warnings.isEmpty())
    }

    /** Row 2: a delete that fails says so. A non-empty directory cannot be deleted. MUTATION m2: the failure line dropped. */
    @Test fun aDeleteThatFailsSaysSo() {
        val stuck = File(dir, "take.pcm").apply { mkdirs(); File(this, "inside").writeText("x") }
        files.delete(stuck.path)
        queued.single().run()
        assertEquals(listOf("Unable to delete captured audio after terminal processing"), warnings.toList())
    }

    /** Row 3: a null or empty path queues nothing. */
    @Test fun aBlankPathQueuesNothing() {
        files.delete(null)
        files.delete("")
        assertTrue(queued.isEmpty())
    }

    /** Row 4: the length is the audio's own: 32,000 bytes of 16 kHz mono PCM is 1,000 ms; no file is 0. */
    @Test fun theLengthIsTheAudiosOwn() {
        val audio = File(dir, "take.pcm").apply { writeBytes(ByteArray(32_000)) }
        assertEquals(1_000L, files.durationMs(audio.path))
        assertEquals(0L, files.durationMs(File(dir, "missing.pcm").path))
        assertEquals(0L, files.durationMs(null))
    }

    /** Row 5 (#373): the copy is made from the file before the delete removes it. MUTATION m5: archive after delete. */
    @Test fun theArchiveCopiesTheFileBeforeTheDelete() {
        val audio = File(dir, "take.pcm").apply { writeText("voice") }
        files.delete(audio.path)
        queued.single().run()
        assertEquals(listOf("voice"), kept.toList())
        assertFalse(audio.exists())
    }

    /** Row 6 (#373): a copy that fails says so and the delete still runs. MUTATION m6: the archive outside its own catch. */
    @Test fun aFailedCopyStillDeletesAndSaysSo() {
        archiveThrows = true
        val audio = File(dir, "take.pcm").apply { writeText("voice") }
        files.delete(audio.path)
        queued.single().run()
        assertFalse(audio.exists())
        assertEquals(listOf("Unable to keep captured audio: IOException"), warnings.toList())
    }

    /** Row 8 (#373): a failed copy whose warning itself throws still deletes. MUTATION m9: the warning outside its catch. */
    @Test fun aThrowingWarningNeverStopsTheDelete() {
        val files = CapturedAudioFiles(
            execute = { queued += it },
            warn = { throw IllegalStateException("log down") },
            archive = { throw RecordingArchive.NotKept("recordings storage unavailable") },
        )
        val audio = File(dir, "take.pcm").apply { writeText("voice") }
        files.delete(audio.path)
        queued.single().run()
        assertFalse(audio.exists())
    }

    /** Row 7 (#373): a take the archive could not keep is logged by its reason, and the delete still runs. MUTATION m7: class name only. */
    @Test fun aNotKeptReasonReachesTheWarning() {
        val files = CapturedAudioFiles(execute = { queued += it }, warn = { warnings += it }, archive = {
            throw RecordingArchive.NotKept("recording was gone before it could be kept")
        })
        val audio = File(dir, "take.pcm").apply { writeText("voice") }
        files.delete(audio.path)
        queued.single().run()
        assertFalse(audio.exists())
        assertEquals(listOf("Unable to keep captured audio: recording was gone before it could be kept"), warnings.toList())
    }
}
