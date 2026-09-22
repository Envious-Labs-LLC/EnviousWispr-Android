package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract (#212, #221): the capture-file naming a late answer's safety rests on. Two takes never share a
 * file, a legacy capture is named by its own token, and a production take's start sweeps only earlier
 * production files and the pre-#212 name. Expected values are literals.
 */
class CaptureFilesTest {

    private val takeA = "3f2a9c1e-7b4d-4e8a-9c21-0d5e6f7a8b9c"
    private val takeB = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    // REVERT R12 (nameFor returns the constant recording.pcm) turns this red.
    @Test
    fun twoTakesNeverShareAFile() {
        assertEquals("recording-take-3f2a9c1e-7b4d-4e8a-9c21-0d5e6f7a8b9c.pcm", CaptureFiles.nameFor(takeA, 1L))
        assertEquals("recording-take-a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d.pcm", CaptureFiles.nameFor(takeB, 1L))
        assertNotEquals(CaptureFiles.nameFor(takeA, 1L), CaptureFiles.nameFor(takeB, 1L))
    }

    // REVERT R12 and R13 (drop the id shape check) turn this red.
    @Test
    fun aHostileOrMissingIdUsesTheUniqueLegacyName() {
        listOf("", "../etc", "a/b", "a".repeat(65), "x.pcm").forEach { id ->
            assertEquals("id <$id>", "recording-legacy-42.pcm", CaptureFiles.nameFor(id, 42L))
            assertFalse("id <$id> is not a production take", CaptureFiles.isProductionTake(id))
        }
        assertEquals("recording-legacy-43.pcm", CaptureFiles.nameFor("", 43L))
    }

    // REVERT R14 (isSweptAtTakeStart matches any recording-*.pcm) turns this red.
    @Test
    fun theTakeStartSweepMatchesOnlyProductionFiles() {
        assertTrue(CaptureFiles.isSweptAtTakeStart("recording.pcm"))
        assertTrue(CaptureFiles.isSweptAtTakeStart("recording-take-3f2a9c1e-7b4d-4e8a-9c21-0d5e6f7a8b9c.pcm"))
        listOf(
            "recording-legacy-42.pcm",
            "enviouswispr-uat.pcm",
            "recording-take-.pcm",
            "recording-take-a.pcm.tmp",
            "xrecording-take-a.pcm",
            "recording-take-a/b.pcm",
        ).forEach { name -> assertFalse("<$name> is never swept", CaptureFiles.isSweptAtTakeStart(name)) }
    }
}
