package com.envi.wispr.recovery

import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.EnglishSpelling
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTerm
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Exact version-one UTF-16 format. Call only on a worker; authenticate its frame before decode. */
internal object RecoveryContextCodec {
    private const val MAGIC = 0x4557524354583031L // EWRCTX01
    private const val VERSION = 1
    private const val USER_TERMS = 2_000
    private const val USER_ALIASES = 2_000
    private const val TERM_UNITS = 200
    private const val ALIASES_PER_TERM = 256
    private const val PROVENANCE_UNITS = 256
    // Independent recovery admission bound, not an assertion about Unicode lowercase expansion.
    private const val PROTECTED_WORD_UNITS = 8_192
    private const val PROTECTED_WORD_COUNT = USER_TERMS * (TERM_UNITS + 1)
    private val maxTerms get() = USER_TERMS + BuiltinVocabulary.terms.size
    private val maxAliases get() = USER_ALIASES + BuiltinVocabulary.terms.sumOf { it.aliases.size }

    fun digest(encoded: ByteArray): RecoveryContextDigest =
        RecoveryContextDigest.of(MessageDigest.getInstance("SHA-256").digest(encoded))

    fun encode(context: FrozenRecoveryContext): ByteArray {
        val measured = Writer(null).also { write(it, context) }.size
        val bytes = ByteArray(measured)
        val writer = Writer(ByteBuffer.wrap(bytes))
        write(writer, context)
        if (writer.size != measured) malformed()
        return bytes
    }

    fun decode(encoded: ByteArray, expectedTake: UUID): FrozenRecoveryContext {
        if (encoded.size > RecoveryFrameLimits.CONTEXT_BYTES) malformed()
        val input = Reader(ByteBuffer.wrap(encoded))
        if (input.long() != MAGIC || input.short() != VERSION) malformed()
        val take = UUID(input.long(), input.long())
        if (take != expectedTake) throw RecoveryFrameException(RecoveryFrameFailure.WRONG_RECORD)
        val createdAt = input.long()
        if (input.int() != PcmAudio.SAMPLE_RATE || input.int() != 1 || input.int() != PcmAudio.BYTES_PER_SAMPLE) malformed()
        val recipe = input.string(PROVENANCE_UNITS)
        val provenance = input.string(PROVENANCE_UNITS)
        val fillers = input.boolean()
        val emoji = input.boolean()
        val punctuation = input.boolean()
        val spelling = when (input.byte()) {
            0 -> EnglishSpelling.AMERICAN
            1 -> EnglishSpelling.BRITISH
            else -> malformed()
        }
        val protected = LinkedHashSet<String>()
        var previous: String? = null
        repeat(input.count(PROTECTED_WORD_COUNT, 4)) {
            val word = input.string(PROTECTED_WORD_UNITS)
            if (previous?.let { it >= word } == true) malformed()
            protected.add(word)
            previous = word
        }
        var aliases = 0
        val terms = ArrayList<CustomTerm>()
        repeat(input.count(maxTerms, 25)) {
            val word = input.string(TERM_UNITS)
            val count = input.count(ALIASES_PER_TERM, 4)
            aliases += count
            if (aliases > maxAliases) malformed()
            val surfaces = List(count) { input.string(TERM_UNITS) }
            val category = if (input.boolean()) input.string(TERM_UNITS) else null
            val priority = input.int()
            val force = input.boolean()
            val caseSensitive = input.boolean()
            val similarity = if (input.boolean()) Double.fromBits(input.long()) else null
            val usage = input.long()
            val imported = input.boolean()
            terms.add(CustomTerm(word, surfaces, category, priority, force, caseSensitive, similarity, usage, imported))
        }
        if (input.remaining != 0) malformed()
        val context = FrozenRecoveryContext.freeze(
            take, createdAt, recipe, provenance,
            CleanupOptions(fillers, emoji, punctuation, spelling, protected), terms,
        )
        // Reuse the encoder's semantic checks without allocating a second encoded context.
        write(Writer(null), context)
        return context
    }

    fun checkCollectionBounds(terms: List<CustomTerm>, protectedWords: Set<String>) {
        if (terms.size > maxTerms || protectedWords.size > PROTECTED_WORD_COUNT) malformed()
        var aliases = 0L
        terms.forEach {
            if (it.aliases.size > ALIASES_PER_TERM) malformed()
            aliases += it.aliases.size
        }
        if (aliases > maxAliases) malformed()
    }

    private fun write(out: Writer, context: FrozenRecoveryContext) {
        checkCollectionBounds(context.terms, context.cleanup.spellingProtectedWords)
        out.long(MAGIC); out.short(VERSION)
        out.long(context.takeId.mostSignificantBits); out.long(context.takeId.leastSignificantBits)
        out.long(context.createdAtMs)
        out.int(PcmAudio.SAMPLE_RATE); out.int(1); out.int(PcmAudio.BYTES_PER_SAMPLE)
        out.string(context.recipeId, PROVENANCE_UNITS); out.string(context.formatProvenance, PROVENANCE_UNITS)
        out.boolean(context.cleanup.removeFillers); out.boolean(context.cleanup.spokenEmoji); out.boolean(context.cleanup.spokenPunctuation)
        out.byte(when (context.cleanup.englishSpelling) {
            EnglishSpelling.AMERICAN -> 0
            EnglishSpelling.BRITISH -> 1
        })
        val protected = context.cleanup.spellingProtectedWords.sorted()
        out.int(protected.size)
        protected.forEach { out.string(it, PROTECTED_WORD_UNITS) }
        out.int(context.terms.size)
        context.terms.forEach { term ->
            if (term.priority !in -100..100 || term.usageCount < 0 ||
                term.minSimilarityOverride?.let { !it.isFinite() || it !in 0.0..1.0 } == true) malformed()
            out.string(term.spelling, TERM_UNITS)
            out.int(term.aliases.size)
            term.aliases.forEach { out.string(it, TERM_UNITS) }
            out.boolean(term.category != null)
            term.category?.let { out.string(it, TERM_UNITS) }
            out.int(term.priority); out.boolean(term.forceReplace); out.boolean(term.caseSensitive)
            out.boolean(term.minSimilarityOverride != null)
            term.minSimilarityOverride?.let { out.long(it.toRawBits()) }
            out.long(term.usageCount); out.boolean(term.imported)
        }
    }

    private class Writer(private val output: ByteBuffer?) {
        var size = 0
            private set
        private fun reserve(count: Int) {
            if (count < 0 || size > RecoveryFrameLimits.CONTEXT_BYTES - count) malformed()
            size += count
        }
        fun byte(value: Int) { reserve(1); output?.put(value.toByte()) }
        fun boolean(value: Boolean) = byte(if (value) 1 else 0)
        fun short(value: Int) { reserve(2); output?.putShort(value.toShort()) }
        fun int(value: Int) { reserve(4); output?.putInt(value) }
        fun long(value: Long) { reserve(8); output?.putLong(value) }
        fun string(value: String, max: Int) {
            if (value.length > max) malformed()
            int(value.length)
            reserve(value.length * 2)
            if (output != null) value.forEach { output.putChar(it) }
        }
    }

    private class Reader(private val input: ByteBuffer) {
        val remaining get() = input.remaining()
        private fun requireBytes(count: Int) { if (count < 0 || count > remaining) malformed() }
        fun byte(): Int { requireBytes(1); return input.get().toInt() and 255 }
        fun boolean(): Boolean = when (byte()) { 0 -> false; 1 -> true; else -> malformed() }
        fun short(): Int { requireBytes(2); return input.short.toInt() and 65535 }
        fun int(): Int { requireBytes(4); return input.int }
        fun long(): Long { requireBytes(8); return input.long }
        fun count(max: Int, minimumBytes: Int): Int = int().also {
            if (it !in 0..max || it > remaining / minimumBytes) malformed()
        }
        fun string(max: Int): String {
            val count = count(max, 2)
            return String(CharArray(count) { input.char })
        }
    }

    private fun malformed(): Nothing = throw RecoveryFrameException(RecoveryFrameFailure.MALFORMED)
}
