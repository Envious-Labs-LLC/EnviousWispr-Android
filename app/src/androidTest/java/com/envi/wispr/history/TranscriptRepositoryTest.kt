package com.envi.wispr.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.envi.wispr.ui.RescuedWords
import com.envi.wispr.ui.RescueOutcome
import java.io.File
import java.util.UUID
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.polish.PolishEngineLabels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        repository = TranscriptRepository(database.transcriptDao(), clock = { 4_242L })
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

    private fun recovered(takeId: String, text: String = "Recovered words.") =
        row(TranscriptEntity.STATUS_COMPLETED, text).copy(
            originalText = "raw recovered words", durationMs = 128L,
            polishEngine = PolishEngineLabels.OFF, insertionResult = InsertionResults.HISTORY_ONLY,
            recovered = true, takeId = takeId, recoveryStartSample = 320L,
            recoveryPartial = true, recoveryModelIdentity = "synthetic-model-v1",
        )

    @Test fun cancelBeforeDraftCreationPreventsBothLateDraftAndAudioRecovery() = runBlocking {
        val kept = repository.insert(row(TranscriptEntity.STATUS_COMPLETED, "Keep these finished words."))
        repository.discardTakeForGood("canceled-before-draft")
        val lateDraft = repository.insert(row(TranscriptEntity.STATUS_DRAFT).copy(takeId = "canceled-before-draft"))
        val lateRecovery = repository.saveRecovered(recovered("canceled-before-draft"))
        val remaining = repository.transcripts.first()
        assertEquals(0L, lateDraft)
        assertEquals(RecoverySaveOutcome.Deleted, lateRecovery)
        assertTrue(repository.isTakeDeleted("canceled-before-draft"))
        assertEquals(listOf(kept), remaining.map { it.id })
    }

    @Test fun audioRecoveryFillsTheEmptyDraftOnceAndPreservesItsUserFacts() = runBlocking {
        val id = repository.insert(row(TranscriptEntity.STATUS_DRAFT).copy(
            takeId = "empty-draft", kept = true, captureDevice = "AirPods Pro 3", liveAfterMs = 22L,
        ))
        assertEquals(RecoverySaveOutcome.Saved(id), repository.saveRecovered(recovered("empty-draft")))
        val saved = repository.transcripts.first().single()
        assertEquals(id, saved.id)
        assertEquals("Recovered words.", saved.finalText)
        assertEquals("raw recovered words", saved.originalText)
        assertEquals(128L, saved.durationMs)
        assertEquals(4_242L, saved.stateChangedAtMs)
        assertEquals(320L, saved.recoveryStartSample)
        assertEquals(true, saved.recoveryPartial)
        assertEquals("synthetic-model-v1", saved.recoveryModelIdentity)
        assertEquals(InsertionResults.HISTORY_ONLY, saved.insertionResult)
        assertEquals(PolishEngineLabels.OFF, saved.polishEngine)
        assertTrue(saved.recovered && saved.kept && !saved.interrupted)
        assertEquals("AirPods Pro 3", saved.captureDevice)
        assertEquals(22L, saved.liveAfterMs)
    }

    @Test fun laterRecoveryPreservesExistingWordsAndEveryStoredRecoveryFact() = runBlocking {
        val first = repository.saveRecovered(recovered("one-result")) as RecoverySaveOutcome.Saved
        val before = repository.transcripts.first().single()
        assertEquals("Recovered words.", before.finalText)
        val late = recovered("one-result", "Wrong late words.").copy(
            durationMs = 999L, recoveryStartSample = 0L, recoveryPartial = false, recoveryModelIdentity = "other-model",
        )
        assertEquals(RecoverySaveOutcome.PreservedExisting(first.id), repository.saveRecovered(late))
        assertEquals(listOf(before), repository.transcripts.first())
    }

    @Test fun concurrentDiscardAndRecoveryCannotLeaveResurrectedWords() = runBlocking {
        val entered = CountDownLatch(2)
        val start = CompletableDeferred<Unit>()
        val save = async(Dispatchers.IO) { entered.countDown(); start.await(); repository.saveRecovered(recovered("racing-discard")) }
        val discard = async(Dispatchers.IO) { entered.countDown(); start.await(); repository.discardTakeForGood("racing-discard") }
        assertTrue("Both real Room operations must be ready", entered.await(10, TimeUnit.SECONDS))
        start.complete(Unit)
        val answer = withTimeout(10_000L) { discard.await(); save.await() }
        assertTrue(answer is RecoverySaveOutcome.Saved || answer == RecoverySaveOutcome.Deleted)
        assertTrue(repository.isTakeDeleted("racing-discard"))
        assertTrue(repository.transcripts.first().isEmpty())
    }

    @Test fun deleteAllIncludesAProtectedTakeThatHasNoHistoryRowYet() = runBlocking {
        repository.insert(row(TranscriptEntity.STATUS_COMPLETED, "Existing words.").copy(takeId = "existing-row"))
        repository.deleteAll(listOf("pending-protected-take"))
        assertEquals(RecoverySaveOutcome.Deleted, repository.saveRecovered(recovered("pending-protected-take")))
        assertTrue(repository.isTakeDeleted("existing-row"))
        assertTrue(repository.transcripts.first().isEmpty())
    }

    @Test fun aFreshTextReaderWritesRecoveryProvenanceIntoRealRoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val take = UUID.randomUUID().toString()
        val dir = File(context.noBackupFilesDir, "recovery-text-test-$take")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = RescuedWords(dir, scope, wallClock = { 999L }, warn = {})
            val failedSave = CompletableDeferred<RecoverySaveOutcome>().apply {
                complete(RecoverySaveOutcome.Failed(RecoveryWriteFailure.WRITE_FAILED))
            }
            assertEquals(RescueOutcome.KEPT,
                withTimeout(10_000L) { first.keepRecovered(RecoveredWordsRecord(recovered(take), 2_048L), failedSave).await() })
            val file = File(dir, "$take.recovery-words-v1")
            assertTrue(file.isFile)
            val restarted = RescuedWords(dir, scope, wallClock = { 999L }, warn = {})
            assertEquals(1, restarted.recover(repository))
            val saved = requireNotNull(database.transcriptDao().findByTakeId(take))
            assertEquals("Recovered words.", saved.finalText)
            assertEquals("raw recovered words", saved.originalText)
            assertEquals(320L, saved.recoveryStartSample)
            assertEquals(true, saved.recoveryPartial)
            assertEquals("synthetic-model-v1", saved.recoveryModelIdentity)
            assertEquals(InsertionResults.HISTORY_ONLY, saved.insertionResult)
            assertEquals(0, restarted.recover(repository))
            assertFalse(file.exists())
        } finally {
            scope.cancel()
            listOf("$take.recovery-words-v1", "$take.recovery-words-v1.tmp").forEach { File(dir, it).delete() }
            dir.delete()
        }
    }
}
