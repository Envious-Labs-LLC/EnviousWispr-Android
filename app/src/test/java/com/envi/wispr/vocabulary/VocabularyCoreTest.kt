package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyCoreTest {
    @Test fun exportRefusesToSilentlyTruncateAnOversizedLibrary() {
        val result = runCatching {
            VocabularyTransfer.export((1..2_001).map { CustomTerm("ExampleTerm$it") })
        }

        assertTrue(result.isFailure)
    }

    @Test fun transferRoundTripPreservesSchema() {
        val source = listOf(
            CustomTerm(
                "EnviousWispr",
                listOf("envious whisper"),
                "product",
                7,
                forceReplace = true,
                caseSensitive = true,
                minSimilarityOverride = 0.72,
            ),
        )
        val preview = VocabularyTransfer.preview(VocabularyTransfer.export(source))
        assertEquals(source, preview.accepted)
        assertEquals(0, preview.rejected)
    }

    @Test fun transferRoundTripPreservesAdversarialText() {
        val source = listOf(
            CustomTerm(
                spelling = "Café\\n東京\tvoice",
                aliases = listOf("comma, alias", "literal \\n and \\t", "unknown \\q"),
                category = "地域, \uD83C\uDF0F",
                priority = -12,
                forceReplace = true,
                caseSensitive = true,
                imported = true,
            ),
        )

        val preview = VocabularyTransfer.preview(VocabularyTransfer.export(source))

        assertEquals("source=$source accepted=${preview.accepted}", source, preview.accepted)
        assertTrue(preview.collisions.isEmpty())
        assertEquals(0, preview.rejected)
    }

    @Test fun importPreviewSeparatesCollisionsAndCommitDoesNotMutate() {
        val existing = listOf(CustomTerm("alpha"))
        val preview = VocabularyTransfer.preview("alpha\nbeta", existing)
        assertEquals(listOf("beta"), preview.accepted.map { it.spelling })
        assertEquals(listOf("alpha"), preview.collisions.map { it.spelling })
        assertEquals(listOf("alpha", "beta"), VocabularyTransfer.commit(existing, preview).map { it.spelling })
        assertEquals(1, existing.size)
    }

    @Test fun malformedAndOversizedRowsAreRejected() {
        val preview = VocabularyTransfer.preview("a\tb\n${"x".repeat(201)}")
        assertEquals(2, preview.rejected)
        assertTrue(preview.accepted.isEmpty())
    }

    @Test fun macBackupPreservesAliasesAndAuthorityFields() {
        val backup = """
            {
              "format": "com.enviouswispr.custom-words",
              "version": 1,
              "words": [{
                "id": "9b92b6be-b9a3-45a8-9b59-41a3d75e3f91",
                "canonical": "EnviousWispr",
                "aliases": ["envious whisper", "envious wisper"],
                "category": "brand",
                "priority": 9,
                "forceReplace": true,
                "caseSensitive": false
                ,"minSimilarityOverride": 0.92
              }]
            }
        """.trimIndent()

        val preview = VocabularyTransfer.preview(backup, listOf(CustomTerm("EnviousWispr")))

        assertTrue(preview.replaceCollisions)
        assertTrue(preview.accepted.isEmpty())
        assertEquals(
            CustomTerm(
                "EnviousWispr",
                listOf("envious whisper", "envious wisper"),
                "brand",
                9,
                forceReplace = true,
                minSimilarityOverride = 0.92,
                imported = true,
            ),
            preview.collisions.single(),
        )
    }

    @Test fun macBackupRejectsUnknownFormatAndFutureVersion() {
        val wrongFormat = runCatching { VocabularyTransfer.preview("""{"format":"other","version":1,"words":[]}""") }
        val future = runCatching {
            VocabularyTransfer.preview("""{"format":"com.enviouswispr.custom-words","version":2,"words":[]}""")
        }
        assertTrue(wrongFormat.isFailure)
        assertTrue(future.isFailure)
    }

    @Test fun liveMacLibraryImportsEveryAliasAndAuthorityField() {
        val liveLibrary = """
            {
              "version": 1,
              "builtinsVersion": 1,
              "deletedBuiltinIds": [],
              "words": [{
                "canonical": "ExampleSDK",
                "aliases": ["example sdk", "example ess dee kay"],
                "category": "product",
                "priority": 10,
                "forceReplace": true,
                "caseSensitive": false,
                "minSimilarityOverride": 0.72,
                "frequencyUsed": 27,
                "enrichmentPending": false
              }]
            }
        """.trimIndent()

        val preview = VocabularyTransfer.preview(liveLibrary)

        assertTrue(preview.replaceCollisions)
        assertEquals(
            CustomTerm(
                spelling = "ExampleSDK",
                aliases = listOf("example sdk", "example ess dee kay"),
                category = "product",
                priority = 10,
                forceReplace = true,
                minSimilarityOverride = 0.72,
                usageCount = 27,
                imported = true,
            ),
            preview.accepted.single(),
        )
    }

    @Test fun arbitraryJsonWithWordsIsNotAcceptedAsLiveMacLibrary() {
        val result = runCatching {
            VocabularyTransfer.preview("""{"version":1,"words":[]}""")
        }

        assertTrue(result.isFailure)
    }

    @Test fun liveMacLibraryWithBuiltinTombstonesFailsClosed() {
        val result = runCatching {
            VocabularyTransfer.preview(
                """{"version":1,"builtinsVersion":1,"deletedBuiltinIds":["builtin-id"],"words":[]}""",
            )
        }

        assertTrue(result.isFailure)
    }

    @Test fun nullFormatAndStringVersionsAreRejected() {
        val nullFormat = runCatching {
            VocabularyTransfer.preview(
                """{"format":null,"version":1,"builtinsVersion":1,"deletedBuiltinIds":[],"words":[]}""",
            )
        }
        val stringVersions = runCatching {
            VocabularyTransfer.preview(
                """{"version":"1","builtinsVersion":"1","deletedBuiltinIds":[],"words":[]}""",
            )
        }

        assertTrue(nullFormat.isFailure)
        assertTrue(stringVersions.isFailure)
    }

    @Test fun malformedMacAuthorityFieldsRejectOnlyThatRow() {
        val input = """
            {
              "version": 1,
              "builtinsVersion": 1,
              "deletedBuiltinIds": [],
              "words": [
                {"canonical":"GoodTerm","aliases":["good term"],"priority":2},
                {"canonical":"BadTerm","aliases":["bad term"],"forceReplace":"true"},
                {"canonical":42,"aliases":[]}
              ]
            }
        """.trimIndent()

        val preview = VocabularyTransfer.preview(input)

        assertEquals(listOf("GoodTerm"), preview.accepted.map { it.spelling })
        assertEquals(2, preview.rejected)
    }

    @Test fun inputAndAliasCountCeilingsFailClosed() {
        val tooLarge = runCatching { VocabularyTransfer.preview("x".repeat(2_000_001)) }
        val aliases = (0..256).joinToString(",") { "alias-$it" }
        val tooManyAliases = VocabularyTransfer.preview(
            "enviouswispr-vocabulary-v1\nExampleSDK\t$aliases\t\t0\tfalse\tfalse\ttrue",
        )

        assertTrue(tooLarge.isFailure)
        assertEquals(1, tooManyAliases.rejected)
        assertTrue(tooManyAliases.accepted.isEmpty())
    }

    @Test fun futurePlainTransferAndMalformedStructuredFieldsAreRejected() {
        val future = runCatching {
            VocabularyTransfer.preview("enviouswispr-vocabulary-v3\nExampleSDK")
        }
        val malformed = VocabularyTransfer.preview(
            "enviouswispr-vocabulary-v1\nExampleSDK\talias\tproduct\tnine\tyes\tfalse\ttrue\textra",
        )

        assertTrue(future.isFailure)
        assertEquals(1, malformed.rejected)
        assertTrue(malformed.accepted.isEmpty())
    }

    @Test fun plainTransferRejectsLongAliasesAndCategories() {
        val long = "x".repeat(201)
        val longAlias = VocabularyTransfer.preview(
            "enviouswispr-vocabulary-v1\nExampleSDK\t$long\tproduct\t0\tfalse\tfalse\ttrue",
        )
        val longCategory = VocabularyTransfer.preview(
            "enviouswispr-vocabulary-v1\nExampleSDK\talias\t$long\t0\tfalse\tfalse\ttrue",
        )

        assertEquals(1, longAlias.rejected)
        assertEquals(1, longCategory.rejected)
    }
}
