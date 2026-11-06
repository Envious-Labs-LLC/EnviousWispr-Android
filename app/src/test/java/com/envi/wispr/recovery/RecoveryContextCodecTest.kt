package com.envi.wispr.recovery

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.EnglishSpelling
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/** Product Outcome: recovery must preserve the original effective word choices without lossy exports. */
class RecoveryContextCodecTest {
    private val take = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    private fun context(
        terms: List<CustomTerm> = emptyList(),
        options: CleanupOptions = CleanupOptions(false, false, false),
    ) = FrozenRecoveryContext.freeze(take, 1L, "r", "p", options, terms)

    private val literal: ByteArray get() = hex(
        "4557524354583031" + "0001" + "123e4567e89b12d3a456426614174000" +
            "0000000000000001" + "00003e80" + "00000001" + "00000002" +
            "000000010072" + "000000010070" + "00000000" + "00000000" + "00000000",
    )

    @Test fun readsAndWritesTheIndependentVersionOneLiteral() {
        assertEquals(70, literal.size)
        assertArrayEquals(literal, RecoveryContextCodec.encode(context()))
        val decoded = RecoveryContextCodec.decode(literal, take)
        assertEquals(take, decoded.takeId)
        assertEquals(1L, decoded.createdAtMs)
        assertEquals("r", decoded.recipeId)
        assertEquals("p", decoded.formatProvenance)
        assertEquals(CleanupOptions(false, false, false), decoded.cleanup)
        assertEquals(emptyList<CustomTerm>(), decoded.terms)
    }

    @Test fun preservesEveryFieldOrderNullEmptyAndExactUtf16Units() {
        val terms = listOf(
            CustomTerm("a\u0000\ud800\udc00\ud83d\ude80", listOf("\udc00", "\ud800", "", "a", "a"), "", -100, true, true, -0.0, Long.MAX_VALUE, true),
            CustomTerm("", listOf("\u0000"), null, 100, false, false, 1.0, 0L, false),
        )
        val options = CleanupOptions(true, false, true, EnglishSpelling.BRITISH, linkedSetOf("\udc00", "a\u0000", "\ud800", "\ud83d\ude80"))
        val encoded = RecoveryContextCodec.encode(context(terms, options))
        val decoded = RecoveryContextCodec.decode(encoded, take)
        assertEquals(terms, decoded.terms)
        assertEquals(options, decoded.cleanup)
        assertEquals(Long.MIN_VALUE, decoded.terms[0].minSimilarityOverride!!.toRawBits())
        assertArrayEquals(encoded, RecoveryContextCodec.encode(decoded))
        val reversedSet = options.copy(spellingProtectedWords = options.spellingProtectedWords.reversed().toSet())
        assertArrayEquals(encoded, RecoveryContextCodec.encode(context(terms, reversedSet)))
    }

    @Test fun freezesOwnedCollectionsRatherThanFollowingLaterSettingsChanges() {
        val aliases = mutableListOf("git hub")
        val terms = mutableListOf(CustomTerm("GitHub", aliases))
        val protected = mutableSetOf("colour")
        val frozen = context(terms, CleanupOptions(spellingProtectedWords = protected))
        aliases[0] = "changed"; terms.clear(); protected.clear()
        assertEquals(listOf(CustomTerm("GitHub", listOf("git hub"))), frozen.terms)
        assertEquals(setOf("colour"), frozen.cleanup.spellingProtectedWords)
        assertThrows(UnsupportedOperationException::class.java) { (frozen.terms as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.terms[0].aliases as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (frozen.cleanup.spellingProtectedWords as MutableSet).clear() }
    }

    @Test fun recoveredMatcherUsesTheFrozenTermsAndEmptyFallbackStaysEmpty() {
        val source = context(listOf(CustomTerm("GitHub", listOf("git hub"), forceReplace = true)))
        val restored = RecoveryContextCodec.decode(RecoveryContextCodec.encode(source), take)
        assertEquals("Send it to GitHub", StructuredTermRestorer.restore("Send it to git hub", restored.terms))
        val fallback = context(emptyList(), CleanupOptions(spellingProtectedWords = setOf("colour")))
        val decoded = RecoveryContextCodec.decode(RecoveryContextCodec.encode(fallback), take)
        assertEquals("Send it to git hub", StructuredTermRestorer.restore("Send it to git hub", decoded.terms))
        assertEquals(setOf("colour"), decoded.cleanup.spellingProtectedWords)
    }

    @Test fun refusesTruncationTrailingDataAnotherTakeAndOversizedInput() {
        for (size in 0 until literal.size) malformed { RecoveryContextCodec.decode(literal.copyOf(size), take) }
        malformed { RecoveryContextCodec.decode(literal + byteArrayOf(0), take) }
        malformed { RecoveryContextCodec.decode(ByteArray(16_777_217), take) }
        val wrong = assertThrows(RecoveryFrameException::class.java) { RecoveryContextCodec.decode(literal, UUID(0L, 0L)) }
        assertEquals(RecoveryFrameFailure.WRONG_RECORD, wrong.reason)
    }

    @Test fun refusesUnknownTagsInvalidPcmAndImpossibleLengthsBeforeAllocating() {
        listOf(0 to 0, 9 to 2, 37 to 1, 41 to 2, 45 to 4, 58 to 2, 59 to 2, 60 to 2, 61 to 2).forEach { (offset, value) ->
            malformed { RecoveryContextCodec.decode(literal.also { it[offset] = value.toByte() }, take) }
        }
        listOf(46 to -1, 46 to Int.MAX_VALUE, 62 to -1, 62 to Int.MAX_VALUE, 66 to 2_012).forEach { (offset, value) ->
            malformed { RecoveryContextCodec.decode(literal.also { ByteBuffer.wrap(it).putInt(offset, value) }, take) }
        }
    }

    @Test fun refusesInvalidTermNumbersAndOverlongFieldsInsteadOfNormalizingThem() {
        listOf(
            CustomTerm("x", priority = 101), CustomTerm("x", priority = -101),
            CustomTerm("x", usageCount = -1), CustomTerm("x", minSimilarityOverride = Double.NaN),
            CustomTerm("x", minSimilarityOverride = Double.POSITIVE_INFINITY), CustomTerm("x", minSimilarityOverride = -0.1),
            CustomTerm("x", minSimilarityOverride = 1.1), CustomTerm("x".repeat(201)),
            CustomTerm("x", listOf("a".repeat(201))), CustomTerm("x", category = "c".repeat(201)),
        ).forEach { term -> malformed { RecoveryContextCodec.encode(context(listOf(term))) } }
        malformed { context(listOf(CustomTerm("x", List(257) { "a" }))) }
        malformed { context(List(2_012) { CustomTerm("x") }) }
        malformed { context(List(8) { CustomTerm("x", List(256) { "a" }) }) }
    }

    @Test fun refusesMalformedNullableTagsAndTermValuesOnReadAsWellAsWrite() {
        // One literal one-unit spelling, no aliases/category/similarity: flags are fixed byte positions.
        val encoded = RecoveryContextCodec.encode(context(listOf(CustomTerm("x"))))
        assertEquals(97, encoded.size)
        listOf(80, 85, 86, 87, 96).forEach { offset ->
            malformed { RecoveryContextCodec.decode(encoded.copyOf().also { it[offset] = 2 }, take) }
        }
        malformed { RecoveryContextCodec.decode(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(81, 101) }, take) }
        malformed { RecoveryContextCodec.decode(encoded.copyOf().also { ByteBuffer.wrap(it).putLong(88, -1L) }, take) }
        val similarity = RecoveryContextCodec.encode(context(listOf(CustomTerm("x", minSimilarityOverride = 0.5))))
        malformed { RecoveryContextCodec.decode(similarity.also { ByteBuffer.wrap(it).putLong(88, 0x7ff8000000000001L) }, take) }
    }

    @Test fun refusesDuplicateAndUnsortedProtectedWordsInsteadOfSilentlyChangingTheSet() {
        val encoded = RecoveryContextCodec.encode(context(options = CleanupOptions(false, false, false, spellingProtectedWords = setOf("a", "b"))))
        // Protected count at62, first length at66, first unit at70, second length at72, second unit at76.
        assertEquals(82, encoded.size)
        malformed { RecoveryContextCodec.decode(encoded.copyOf().also { ByteBuffer.wrap(it).putChar(76, 'a') }, take) }
        malformed { RecoveryContextCodec.decode(encoded.copyOf().also { ByteBuffer.wrap(it).putChar(70, 'z') }, take) }
    }

    @Test fun admitsCurrentMaximumLibraryWithBuiltinsAndRefusesAnOversizedContext() {
        val terms = BuiltinVocabulary.terms + List(2_000) { CustomTerm("字".repeat(200), listOf("\ud800".repeat(200)), category = "c".repeat(200), priority = 100, usageCount = Long.MAX_VALUE) }
        val decoded = RecoveryContextCodec.decode(RecoveryContextCodec.encode(context(terms)), take)
        assertEquals(terms, decoded.terms)
        // Independently bounded recovery admission can refuse input without changing/truncating it.
        val words = (0 until 1_100).map { it.toString().padStart(8_192, 'x') }.toSet()
        val oversized = context(options = CleanupOptions(spellingProtectedWords = words))
        malformed { RecoveryContextCodec.encode(oversized) }
        assertEquals(1_100, oversized.cleanup.spellingProtectedWords.size)
        assertEquals(8_192, oversized.cleanup.spellingProtectedWords.first().length)
    }

    @Test fun canonicalDigestMatchesAnIndependentSha256Fixture() {
        assertArrayEquals(hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), RecoveryContextCodec.digest(byteArrayOf()).copyBytes())
    }

    private fun malformed(block: () -> Unit) {
        val error = assertThrows(RecoveryFrameException::class.java, block)
        assertEquals(RecoveryFrameFailure.MALFORMED, error.reason)
        assertEquals("Protected recording unavailable", error.message)
        assertEquals(null, error.cause)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
