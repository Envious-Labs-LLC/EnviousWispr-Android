package com.envi.wispr.audio

import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: the user hears at most one matched pair, with no stop before confirmed closure. */
class RecordingSoundCueTest {
    @Test fun aStopRequiresSuccessfulStartAndConfirmedClosure() {
        val heard = mutableListOf<String>()
        val cue = RecordingSoundCue { pair, moment -> heard += "${pair.storageKey}:${moment.name}"; true }
        cue.live(true, RecordingSoundPairing.WHISPER_TICK)
        cue.live(true, RecordingSoundPairing.DUST_MOTE)
        assertEquals(listOf("whisperTick:START"), heard)
        cue.captureClosed(true)
        cue.captureClosed(true)
        assertEquals(listOf("whisperTick:START", "whisperTick:STOP"), heard)
    }
    @Test fun aFailedOrDisabledStartCannotHaveAnUnmatchedStop() {
        val calls = mutableListOf<RecordingSoundMoment>()
        val failed = RecordingSoundCue { _, moment -> calls += moment; false }
        failed.live(true, RecordingSoundPairing.DEFAULT)
        failed.live(true, RecordingSoundPairing.DEFAULT)
        failed.captureClosed(true)
        assertEquals(listOf(RecordingSoundMoment.START), calls)
        calls.clear()
        val off = RecordingSoundCue { _, moment -> calls += moment; true }
        off.live(false, RecordingSoundPairing.DEFAULT)
        off.captureClosed(true)
        assertTrue(calls.isEmpty())
    }
    @Test fun unknownClosureAndTeardownPermanentlyDiscardPendingStop() {
        for (abandoned in listOf(false, true)) {
            val heard = mutableListOf<RecordingSoundMoment>()
            val cue = RecordingSoundCue { _, moment -> heard += moment; true }
            cue.live(true, RecordingSoundPairing.DEFAULT)
            if (abandoned) cue.abandon() else cue.captureClosed(false)
            cue.captureClosed(true)
            cue.live(true, RecordingSoundPairing.DEFAULT)
            assertEquals(listOf(RecordingSoundMoment.START), heard)
        }
    }
    @Test fun aPreLiveEndingNeverProducesAnySound() {
        val heard = mutableListOf<RecordingSoundMoment>()
        val cue = RecordingSoundCue { _, moment -> heard += moment; true }
        cue.captureClosed(true)
        cue.live(true, RecordingSoundPairing.DEFAULT)
        assertTrue(heard.isEmpty())
    }
    @Test fun soundFailureDoesNotThrowIntoRecording() {
        val cue = RecordingSoundCue { _, _ -> error("speaker unavailable") }
        cue.live(true, RecordingSoundPairing.DEFAULT)
        cue.captureClosed(true)
    }
}
