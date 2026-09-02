package com.envi.wispr.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drift Guard (#77): the three polish facts survive every row-writing path. A draft starts with the empty
 * defaults; finalization replaces all three; the ready-row insert stores the same facts. When this fails a
 * History card says nothing about a failure that was announced, or says one that never happened.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptOutcomePersistenceTest {
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

    private fun draft() = TranscriptEntity(
        originalText = "",
        finalText = "",
        createdAtMs = 1L,
        durationMs = 0L,
        speechEngine = "Parakeet",
        polishEngine = "",
        polishLatencyMs = 0L,
        insertionResult = "pending",
        status = TranscriptEntity.STATUS_DRAFT,
    )

    @Test fun aDraftStartsWithTheEmptyDefaultsAndFinalizationReplacesAllThree() = runBlocking {
        val id = repository.insert(draft())
        val stored = repository.transcripts.first().single { it.id == id }
        assertEquals("", stored.polishReason)
        assertEquals(0, stored.polishStatus)
        assertEquals("", stored.polishContext)

        repository.finalize(
            id = id,
            originalText = "raw words",
            finalText = "raw words",
            speechEngine = "Parakeet",
            polishEngine = "Deterministic fallback",
            polishLatencyMs = 0L,
            insertionResult = "pending",
            durationMs = 1_000L,
            polishReason = "HTTP_ERROR",
            polishStatus = 401,
            polishContext = "cloud:GEMINI",
        )
        val finalized = repository.transcripts.first().single { it.id == id }
        assertEquals("HTTP_ERROR", finalized.polishReason)
        assertEquals(401, finalized.polishStatus)
        assertEquals("cloud:GEMINI", finalized.polishContext)
    }

    @Test fun aReadyRowInsertStoresTheSameFacts() = runBlocking {
        val id = repository.insert(
            draft().copy(
                finalText = "raw words",
                status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
                polishReason = "WATCHDOG_TIMEOUT",
                polishStatus = 0,
                polishContext = "local",
            ),
        )
        val stored = repository.transcripts.first().single { it.id == id }
        assertEquals("WATCHDOG_TIMEOUT", stored.polishReason)
        assertEquals(0, stored.polishStatus)
        assertEquals("local", stored.polishContext)
    }
}
