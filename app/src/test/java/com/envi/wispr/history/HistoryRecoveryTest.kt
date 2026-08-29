package com.envi.wispr.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRecoveryTest {
    private val now = 120_000L

    @Test fun longRecordingThatJustBecameReadyIsFresh() {
        assertFalse(HistoryRecovery.isStale(stateChangedAtMs = now - 1_000L, nowMs = now))
    }

    @Test fun staleReadyRowIsRecoverable() {
        assertTrue(HistoryRecovery.isStale(stateChangedAtMs = now - 30_000L, nowMs = now))
        assertTrue(TranscriptEntity.STATUS_READY_FOR_INSERTION != TranscriptEntity.STATUS_COMPLETED)
    }

    @Test fun freshDraftSurvivesStartup() {
        assertFalse(HistoryRecovery.isStale(now - 29_999L, now))
    }

    @Test fun staleDraftAndProcessingAreDistinctFromInsertionInterruption() {
        assertTrue(TranscriptEntity.STATUS_INTERRUPTED != TranscriptEntity.STATUS_INSERTION_INTERRUPTED)
        assertTrue(HistoryRecovery.isStale(now - 60_000L, now))
    }

    @Test fun completedOutcomeIsTerminalAndNotStale() {
        assertFalse(HistoryRecovery.isStale(now, now))
        assertTrue(TranscriptEntity.STATUS_COMPLETED != TranscriptEntity.STATUS_READY_FOR_INSERTION)
    }
}
