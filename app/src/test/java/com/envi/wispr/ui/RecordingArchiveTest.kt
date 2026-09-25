package com.envi.wispr.ui

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/**
 * Product Outcome (#373): the founder can pull the newest ten takes' audio from the phone and play them. When this
 * fails, a take that lost words cannot be replayed, or the folder grows without bound. Each row names its mutation.
 */
class RecordingArchiveTest {
    private val root = Files.createTempDirectory("archive").toFile()
    private val folder = File(root, "recordings")
    private val source = File(root, "cache").apply { mkdirs() }
    private var clock = 1_758_800_000_000L
    private val warnings = mutableListOf<String>()
    private val archive = RecordingArchive(dir = { folder }, warn = { warnings += it }, now = { clock })

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun take(id: String, bytes: ByteArray) = File(source, "recording-take-$id.pcm").apply { writeBytes(bytes) }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** Row 1: the copy is a 16 kHz mono PCM16 WAV whose data is the take's bytes. MUTATION m1: copy without the header. */
    @Test fun aKeptTakeIsAPlayableWavOfTheSameAudio() {
        val pcm = ByteArray(32_000) { (it % 251).toByte() }
        archive.keep(take("abc", pcm))
        val wav = folder.listFiles()!!.single().readBytes()
        val expectedHeader = "RIFF".toByteArray() + le32(36 + 32_000) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(16_000) + le32(32_000) + le16(2) + le16(16) +
            "data".toByteArray() + le32(32_000)
        assertEquals(44 + 32_000, wav.size)
        assertArrayEquals(expectedHeader, wav.copyOfRange(0, 44))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    /**
     * Row 2: the name is the UTC time to the millisecond and the History take id, whatever the phone's zone, and no
     * part file is left. MUTATION m2: name by source only, or stamp in local time.
     */
    @Test fun theNameCarriesTheTimeAndTheTakeId() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            archive.keep(take("1aed846a-d719", ByteArray(10)))
        } finally {
            TimeZone.setDefault(zone)
        }
        assertEquals(listOf("20250925-113320-000-1aed846a-d719.wav"), folder.list()!!.toList())
    }

    /** Row 3: eleven takes leave the newest ten. MUTATION m3: no prune. */
    @Test fun elevenTakesLeaveTheNewestTen() {
        repeat(11) { i ->
            clock += 1_000
            archive.keep(take("t$i", ByteArray(10)))
        }
        val names = folder.list()!!.sorted()
        assertEquals(10, names.size)
        assertTrue(names.none { it.endsWith("-t0.wav") })
        assertTrue(names.last().endsWith("-t10.wav"))
    }

    /** Row 3b: takes in the same second still prune the OLDEST, not by take id. MUTATION m3b: second-only stamp. */
    @Test fun takesInOneSecondPruneTheOldest() {
        repeat(11) { i ->
            clock += 7
            archive.keep(take("z${10 - i}", ByteArray(10)))
        }
        val names = folder.list()!!.sorted()
        assertEquals(10, names.size)
        assertTrue("the first take went", names.none { it.endsWith("-z10.wav") })
        assertTrue("the last take stayed", names.any { it.endsWith("-z0.wav") })
    }

    /** Row 4: with no shared storage nothing is kept and it says why. MUTATION m4: return silently on null. */
    @Test fun noFolderKeepsNothingAndSaysSo() {
        val error = assertThrows(RecordingArchive.NotKept::class.java) {
            RecordingArchive(dir = { null }, warn = { warnings += it }).keep(take("x", ByteArray(10)))
        }
        assertEquals("recordings storage unavailable", error.message)
        assertFalse(folder.exists())
    }

    /**
     * Row 8: an old part file that cannot be removed is reported and does not stop the take being kept. A non-empty
     * directory named like a part file cannot be deleted. MUTATION m8: the failure throws, or is dropped.
     */
    @Test fun aStuckOldPartIsReportedAndTheTakeIsStillKept() {
        File(folder, "20250101-000000-000-dead.part").apply { mkdirs(); File(this, "inside").writeText("x") }
        archive.keep(take("k", ByteArray(10)))
        assertTrue(File(folder, "20250925-113320-000-k.wav").isFile)
        assertEquals(listOf("Recordings folder: 1 old part files could not be removed"), warnings.toList())
    }

    /** Row 8b: the same stuck part with a warning that throws still keeps the take. MUTATION m10: warn uncaught. */
    @Test fun aThrowingWarningNeverStopsTheCopy() {
        File(folder, "20250101-000000-000-dead.part").apply { mkdirs(); File(this, "inside").writeText("x") }
        RecordingArchive(dir = { folder }, warn = { throw IllegalStateException("log down") }, now = { clock })
            .keep(take("w", ByteArray(10)))
        assertTrue(File(folder, "20250925-113320-000-w.wav").isFile)
    }

    /** Row 5: the take's own file is left for its owner to delete. MUTATION m5: move instead of copy. */
    @Test fun theSourceIsLeftInPlace() {
        val file = take("y", ByteArray(10))
        archive.keep(file)
        assertTrue(file.exists())
    }

    /** Row 6: a part file left by a copy cut short is removed and never counts. MUTATION m6: no part cleanup. */
    @Test fun aLeftoverPartFileIsRemoved() {
        folder.mkdirs()
        File(folder, "20250101-000000-000-dead.part").writeText("half")
        archive.keep(take("z", ByteArray(10)))
        assertEquals(listOf("20250925-113320-000-z.wav"), folder.list()!!.toList())
    }

    /** Row 7: a take whose file was already gone says so, never silently. MUTATION m7: return on a missing source. */
    @Test fun aGoneRecordingSaysSo() {
        val error = assertThrows(RecordingArchive.NotKept::class.java) { archive.keep(File(source, "recording-take-q.pcm")) }
        assertEquals("recording was gone before it could be kept", error.message)
        assertTrue(warnings.isEmpty())
    }
}
