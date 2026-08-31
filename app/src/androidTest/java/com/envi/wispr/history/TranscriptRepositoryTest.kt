package com.envi.wispr.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PRODUCT OUTCOME.
 *
 * When this fails the user opens History and finds it padded with cards for dictations that never
 * produced a word — the microphone heard nothing, or they cancelled — and has to scroll past them to
 * reach their own transcripts.
 *
 * The real Room database, not a fake DAO: the prune is a `DELETE ... WHERE status IN (...)` string
 * and a fake would only prove that a Kotlin filter written twice agrees with itself.
 */
class TranscriptRepositoryTest {
    private lateinit var database: EnviousWisprDatabase
    private lateinit var repository: TranscriptRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EnviousWisprDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = TranscriptRepository(database.transcriptDao())
    }

    @After fun tearDown() = database.close()

    private fun row(status: String, finalText: String = "", createdAtMs: Long = 1L) = TranscriptEntity(
        originalText = finalText,
        finalText = finalText,
        createdAtMs = createdAtMs,
        durationMs = 0L,
        speechEngine = "Parakeet",
        polishEngine = "",
        polishLatencyMs = 0L,
        insertionResult = "pending",
        status = status,
    )

    @Test fun discardRemovesOneRowAndLeavesTheRest() = runBlocking {
        val doomed = repository.insert(row(TranscriptEntity.STATUS_DRAFT))
        val kept = repository.insert(row(TranscriptEntity.STATUS_COMPLETED, "the words I said", createdAtMs = 2L))

        assertEquals(1, repository.discard(doomed))

        val remaining = repository.transcripts.first()
        assertEquals(listOf(kept), remaining.map(TranscriptEntity::id))
    }

    @Test fun discardingAnIdThatIsAlreadyGoneChangesNothing() = runBlocking {
        val only = repository.insert(row(TranscriptEntity.STATUS_COMPLETED, "the words I said"))

        assertEquals(0, repository.discard(only + 1))

        assertEquals(listOf(only), repository.transcripts.first().map(TranscriptEntity::id))
    }

    @Test fun pruningTakesTheWordlessRowsAndNothingElse() = runBlocking {
        val noSpeech = repository.insert(row(TranscriptEntity.STATUS_NO_SPEECH))
        val canceled = repository.insert(row(TranscriptEntity.STATUS_CANCELED, createdAtMs = 2L))
        val draftInFlight = repository.insert(row(TranscriptEntity.STATUS_DRAFT, createdAtMs = 3L))
        val interrupted = repository.insert(row(TranscriptEntity.STATUS_INTERRUPTED, createdAtMs = 4L))
        val completed = repository.insert(
            row(TranscriptEntity.STATUS_COMPLETED, "the words I said", createdAtMs = 5L),
        )

        assertEquals(2, repository.pruneWordlessRows())

        val survivors = repository.transcripts.first().map(TranscriptEntity::id)
        assertTrue(
            "the no-speech row $noSpeech survived the prune",
            noSpeech !in survivors,
        )
        assertTrue(
            "the cancelled row $canceled survived the prune",
            canceled !in survivors,
        )
        // A draft is textless too, and it is the row a dictation currently in flight is writing to.
        // Pruning on empty text rather than on status would delete a live dictation.
        assertEquals(setOf(draftInFlight, interrupted, completed), survivors.toSet())
    }

    @Test fun pruningAPhoneThatHasNoWordlessRowsDeletesNothing() = runBlocking {
        repository.insert(row(TranscriptEntity.STATUS_COMPLETED, "the words I said"))

        assertEquals(0, repository.pruneWordlessRows())

        assertEquals(1, repository.transcripts.first().size)
    }
}
