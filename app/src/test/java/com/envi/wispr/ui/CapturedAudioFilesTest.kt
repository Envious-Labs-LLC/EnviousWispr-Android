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
    private val files = CapturedAudioFiles(execute = { queued += it }, warn = { warnings += it })
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

    /** Row 3: a blank or missing path queues nothing. */
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
}
