package com.envi.wispr.vocabulary

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Manual, test-APK-only bridge for moving a Mac vocabulary backup into the real app database.
 * The source must already be inside the target app's private files directory and is deleted
 * after the verified transaction. Normal device-test runs skip when no source is present.
 */
class MacVocabularyMigrationDeviceTest {
    @Test
    fun importPrivateMacVocabularyAndVerifyEveryAuthorityField() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, SOURCE_NAME)
        assumeTrue("Private Mac vocabulary source is not staged", source.isFile)

        var verified = false
        try {
            val bytes = source.readBytes()
            assertTrue(
                "Mac vocabulary snapshot changed; staged source retained for review",
                sha256(bytes) == EXPECTED_SOURCE_SHA256,
            )
            val input = bytes.toString(StandardCharsets.UTF_8)
            val expected = independentlyParseExpectedTerms(input)
            assertEquals(EXPECTED_WORD_COUNT, expected.size)
            assertEquals(EXPECTED_NORMALIZED_ALIAS_COUNT, expected.sumOf { it.aliases.size })

            val repository = CustomTermRepository(context)
            val existing = repository.list().map(CustomTermRecord::term)
            val preview = VocabularyTransfer.preview(input, existing)
            assertEquals(0, preview.rejected)
            assertTrue(preview.replaceCollisions)

            val planned = (preview.accepted + preview.collisions)
                .associateBy { it.spelling.lowercase(Locale.ROOT) }
            assertEquals(EXPECTED_WORD_COUNT, planned.size)
            expected.forEachIndexed { index, term ->
                val actual = planned[term.spelling.lowercase(Locale.ROOT)]
                assertTrue("Import plan is missing record $index", actual != null)
                assertAuthorityFieldsMatch(term, actual!!, index, "import plan")
            }

            if (existing.isEmpty()) {
                assertEquals(EXPECTED_WORD_COUNT, preview.accepted.size)
                assertEquals(0, preview.collisions.size)
                val result = repository.applyImport(preview)
                assertEquals(EXPECTED_WORD_COUNT, result.added)
                assertEquals(0, result.updated)
                assertEquals(0, result.skipped)
                assertEquals(0, result.rejected)
            } else {
                // Never overwrite a phone library that changed after the migration was staged.
                assertEquals(EXPECTED_WORD_COUNT, existing.size)
                assertEquals(0, preview.accepted.size)
                assertEquals(EXPECTED_WORD_COUNT, preview.collisions.size)
            }

            val stored = repository.list()
                .map(CustomTermRecord::term)
                .associateBy { it.spelling.lowercase(Locale.ROOT) }
            assertEquals(EXPECTED_WORD_COUNT, stored.size)
            expected.forEachIndexed { index, term ->
                val actual = stored[term.spelling.lowercase(Locale.ROOT)]
                assertTrue("Stored library is missing record $index", actual != null)
                assertAuthorityFieldsMatch(term, actual!!, index, "stored library")
            }
            verified = true
        } finally {
            if (verified) {
                assertTrue("Verified private migration source was not deleted", source.delete())
            }
        }
    }

    private fun independentlyParseExpectedTerms(input: String): List<CustomTerm> {
        val root = Json.parseToJsonElement(input).jsonObject
        val words = root.getValue("words").jsonArray
        assertEquals(EXPECTED_WORD_COUNT, words.size)

        var rawAliasCount = 0
        val expected = words.mapIndexed { index, value ->
            val row = value.jsonObject
            val aliases = row.getValue("aliases").jsonArray.mapIndexed { aliasIndex, alias ->
                val primitive = alias.jsonPrimitive
                require(primitive.isString) { "Alias $aliasIndex in record $index is not text" }
                primitive.content
            }
            rawAliasCount += aliases.size
            CustomTerm(
                spelling = requiredString(row, "canonical", index),
                aliases = aliases,
                category = optionalString(row, "category", index),
                priority = optionalInt(row, "priority", index) ?: 0,
                forceReplace = optionalBoolean(row, "forceReplace", index) ?: false,
                caseSensitive = optionalBoolean(row, "caseSensitive", index) ?: false,
                minSimilarityOverride = optionalDouble(row, "minSimilarityOverride", index),
                usageCount = optionalLong(row, "frequencyUsed", index) ?: 0L,
                imported = true,
            ).normalized().also { term ->
                require(term.spelling.isNotEmpty()) { "Record $index has an empty canonical value" }
                require(term.usageCount >= 0L) { "Record $index has a negative usage count" }
            }
        }
        assertEquals(EXPECTED_RAW_ALIAS_COUNT, rawAliasCount)
        assertEquals(
            EXPECTED_WORD_COUNT,
            expected.map { it.spelling.lowercase(Locale.ROOT) }.distinct().size,
        )
        return expected
    }

    private fun requiredString(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): String {
        val primitive = row[name]?.jsonPrimitive
            ?: throw IllegalArgumentException("Record $index is missing $name")
        require(primitive.isString) { "Field $name in record $index is not text" }
        return primitive.content
    }

    private fun optionalString(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): String? {
        val value = row[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(primitive.isString) { "Field $name in record $index is not text" }
        return primitive.content
    }

    private fun optionalInt(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): Int? {
        val value = row[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString) { "Field $name in record $index is not numeric" }
        return primitive.intOrNull
            ?: throw IllegalArgumentException("Field $name in record $index is not an integer")
    }

    private fun optionalLong(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): Long? {
        val value = row[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString) { "Field $name in record $index is not numeric" }
        return primitive.longOrNull
            ?: throw IllegalArgumentException("Field $name in record $index is not an integer")
    }

    private fun optionalBoolean(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): Boolean? {
        val value = row[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString) { "Field $name in record $index is not a boolean" }
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("Field $name in record $index is not a boolean")
    }

    private fun optionalDouble(
        row: Map<String, kotlinx.serialization.json.JsonElement>,
        name: String,
        index: Int,
    ): Double? {
        val value = row[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        require(!primitive.isString) { "Field $name in record $index is not numeric" }
        return primitive.doubleOrNull
            ?: throw IllegalArgumentException("Field $name in record $index is not a number")
    }

    private fun assertAuthorityFieldsMatch(
        expected: CustomTerm,
        actual: CustomTerm,
        index: Int,
        location: String,
    ) {
        assertTrue("Canonical mismatch at $location record $index", expected.spelling == actual.spelling)
        assertTrue("Alias mismatch at $location record $index", expected.aliases == actual.aliases)
        assertTrue("Category mismatch at $location record $index", expected.category == actual.category)
        assertTrue("Priority mismatch at $location record $index", expected.priority == actual.priority)
        assertTrue("Replace flag mismatch at $location record $index", expected.forceReplace == actual.forceReplace)
        assertTrue("Case flag mismatch at $location record $index", expected.caseSensitive == actual.caseSensitive)
        assertTrue(
            "Similarity override mismatch at $location record $index",
            expected.minSimilarityOverride == actual.minSimilarityOverride,
        )
        assertTrue("Usage mismatch at $location record $index", expected.usageCount == actual.usageCount)
        assertTrue("Import flag mismatch at $location record $index", actual.imported)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val SOURCE_NAME = "mac-vocabulary-import.json"
        const val EXPECTED_SOURCE_SHA256 =
            "fc2098542004a053a7c49a1238aa8e9fe248a4f21902ff46c6a5c58b042fdcad"
        const val EXPECTED_WORD_COUNT = 36
        const val EXPECTED_RAW_ALIAS_COUNT = 115
        const val EXPECTED_NORMALIZED_ALIAS_COUNT = 109
    }
}
