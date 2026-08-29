package com.envi.wispr.vocabulary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.envi.wispr.history.EnviousWisprDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomTermRepositoryTest {
    private lateinit var database: EnviousWisprDatabase
    private lateinit var repository: CustomTermRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EnviousWisprDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CustomTermRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun addEditSearchUsageAndDeleteAreStructured() = runBlocking {
        val created = repository.add(CustomTerm(" EnviousWispr ", listOf("whisper app"), "product", 8, true, true), nowMs = 10L)
        assertEquals("EnviousWispr", created.term.spelling)
        assertEquals(listOf(created), repository.search("whisper"))
        assertTrue(repository.incrementUsage(created.id, nowMs = 11L))
        val edited = repository.edit(created.id, created.term.copy(category = "brand", priority = 9), nowMs = 12L)!!
        assertEquals("brand", edited.term.category)
        assertEquals(1L, repository.list().single().term.usageCount)
        assertTrue(repository.delete(created.id))
        assertTrue(repository.list().isEmpty())
    }

    @Test fun bulkDeleteAndCaseInsensitiveCollisionRulesAreTransactional() = runBlocking {
        val first = repository.add(CustomTerm("Acme", caseSensitive = true))
        assertTrue(runCatching { repository.add(CustomTerm("acme", caseSensitive = true)) }.isFailure)
        assertTrue(runCatching { repository.add(CustomTerm("ACME")) }.isFailure)
        assertEquals(1, repository.bulkDelete(listOf(first.id, first.id)))
        assertTrue(repository.list().isEmpty())
    }

    @Test fun macImportUpdatesMatchesAddsNewTermsAndIsIdempotent() = runBlocking {
        repository.add(CustomTerm("EnviousWispr", listOf("old alias"), usageCount = 4), nowMs = 10L)
        val incoming = listOf(
            CustomTerm("EnviousWispr", listOf("envious whisper"), "brand", usageCount = 2, imported = true),
            CustomTerm("ExamplePerson", listOf("example person"), "person", imported = true),
        )
        val firstPreview = ImportPreview(
            accepted = listOf(incoming[1]),
            collisions = listOf(incoming[0]),
            rejected = 0,
            replaceCollisions = true,
        )

        val first = repository.applyImport(firstPreview, nowMs = 20L)
        assertEquals(CustomTermImportResult(1, 1, 0, 0), first)
        val afterFirst = repository.list().associateBy { it.term.spelling }
        assertEquals(listOf("envious whisper"), afterFirst.getValue("EnviousWispr").term.aliases)
        assertEquals(2L, afterFirst.getValue("EnviousWispr").term.usageCount)

        val second = repository.applyImport(
            ImportPreview(emptyList(), incoming, 0, replaceCollisions = true),
            nowMs = 30L,
        )
        assertEquals(CustomTermImportResult(0, 2, 0, 0), second)
        assertEquals(2, repository.list().size)
    }

    @Test fun directRepositoryWritesEnforceVocabularyLimits() = runBlocking {
        val tooManyAliases = runCatching {
            repository.add(CustomTerm("ExampleSDK", aliases = (0..256).map { "alias-$it" }))
        }
        val longAlias = runCatching {
            repository.add(CustomTerm("ExampleSDK", aliases = listOf("x".repeat(201))))
        }
        val longCategory = runCatching {
            repository.add(CustomTerm("ExampleSDK", category = "x".repeat(201)))
        }
        val negativeUsage = runCatching {
            repository.add(CustomTerm("ExampleSDK", usageCount = -1L))
        }

        assertTrue(tooManyAliases.isFailure)
        assertTrue(longAlias.isFailure)
        assertTrue(longCategory.isFailure)
        assertTrue(negativeUsage.isFailure)
        assertTrue(repository.list().isEmpty())
    }

    @Test fun priorityMetadataCannotChangeRuntimeAuthority() = runBlocking {
        val alpha = repository.add(CustomTerm("Alpha", aliases = listOf("ny"), priority = 100))
        val beta = repository.add(CustomTerm("Beta", aliases = listOf("ny"), priority = -100))

        suspend fun restored(): String {
            val terms = repository.list().map(CustomTermRecord::term)
            return StructuredTermRestorer.compile(terms).restore("ny")
        }

        assertEquals("Beta", restored())
        repository.edit(alpha.id, alpha.term.copy(priority = -100))
        repository.edit(beta.id, beta.term.copy(priority = 100))
        assertEquals("Beta", restored())
    }

    @Test fun staleUnicodePreviewCannotCreateDuplicateCanonical() = runBlocking {
        val stalePreview = ImportPreview(
            accepted = listOf(CustomTerm("ångström")),
            collisions = emptyList(),
            rejected = 0,
        )
        repository.add(CustomTerm("Ångström"))

        val result = repository.applyImport(stalePreview)

        assertEquals(CustomTermImportResult(0, 0, 1, 0), result)
        assertEquals(1, repository.list().size)
    }

    @Test fun aggregateAliasLimitBoundsMatcherWork() = runBlocking {
        repeat(8) { termIndex ->
            repository.add(
                CustomTerm(
                    "ExampleTerm$termIndex",
                    aliases = (0 until 250).map { aliasIndex -> "alias-$termIndex-$aliasIndex" },
                ),
            )
        }

        val overflow = runCatching {
            repository.add(CustomTerm("Overflow", aliases = listOf("one-too-many")))
        }

        assertTrue(overflow.isFailure)
        assertEquals(2_000, repository.list().sumOf { it.term.aliases.size })
    }

    @Test fun repeatedImportsCannotGrowPastExportableLibraryLimit() = runBlocking {
        val firstBatch = (1..1_999).map { CustomTerm("ExampleTerm$it") }
        repository.applyImport(ImportPreview(firstBatch, emptyList(), 0))
        repository.add(CustomTerm("ExampleTerm2000"))

        val overflow = runCatching {
            repository.applyImport(
                ImportPreview(listOf(CustomTerm("ExampleTerm2001")), emptyList(), 0),
            )
        }

        assertTrue(overflow.isFailure)
        assertEquals(2_000, repository.list().size)
    }
}
