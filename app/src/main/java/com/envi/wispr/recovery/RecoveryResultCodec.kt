package com.envi.wispr.recovery

import com.envi.wispr.asr.ParakeetEngine
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelManifest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** Facts from the loaded recognizer generation, never inferred from today's preferences. */
internal data class RecoveryModelIdentity(
    val engineId: String,
    val modelId: String,
    val pinnedRevision: String,
    val recipeId: String,
    val displayName: String,
) {
    fun isCompatible(): Boolean = recipeId == ParakeetEngine.RECIPE_ID &&
        (listOf(ModelManifest.parakeet) + ModelManifest.parakeet.previous).any {
            engineId == it.engineId && modelId == it.id && pinnedRevision == it.pinnedRevision && displayName == it.displayName
        }

    fun storedIdentity(): String = RecoveryResultCodec.modelIdentity(this)

    companion object {
        fun loaded(descriptor: ModelDescriptor): RecoveryModelIdentity = RecoveryModelIdentity(
            descriptor.engineId, descriptor.id, descriptor.pinnedRevision, ParakeetEngine.RECIPE_ID, descriptor.displayName,
        )
    }
}

internal data class RecoveryResult(
    val replay: RecoveryReplayRef,
    val firstSample: Long,
    val sampleCount: Long,
    val completedCapture: Boolean,
    val model: RecoveryModelIdentity,
    val rawText: String,
) {
    val partial: Boolean get() = firstSample != 0L || !completedCapture
    val durationMs: Long get() = sampleCount * 1_000L / com.envi.wispr.audio.PcmAudio.SAMPLE_RATE
}

internal class RecoveryResultException : IOException("Recovered recording unavailable")

/** Recovery-only callback envelope. Ordinary speech callbacks remain plain text. */
internal object RecoveryResultCodec {
    private const val PREFIX = "EWR-RESULT-1:"
    private const val MAGIC = 0x4557525245533031L // EWRRES01
    private const val FIXED_BYTES = 127

    fun encode(result: RecoveryResult): String {
        validate(result)
        val strings = modelFields(result.model) + result.rawText
        val size = FIXED_BYTES.toLong() + strings.sumOf { it.length.toLong() * 2L }
        if (size > RecoveryTextLimits.RESULT_BYTES) malformed()
        val out = ByteBuffer.allocate(size.toInt())
        val ref = result.replay.record
        out.putLong(MAGIC); out.putShort(1); out.putInt(ref.slot)
        putUuid(out, ref.storeId); out.putLong(ref.identity.generation)
        putUuid(out, result.replay.attempt); out.put(ref.identity.context.copyBytes())
        out.putLong(result.firstSample); out.putLong(result.sampleCount)
        out.put((if (result.completedCapture) 1 else 0).toByte())
        strings.forEach { putString(out, it) }
        if (out.hasRemaining()) malformed()
        return (PREFIX + Base64.getEncoder().encodeToString(out.array())).also {
            if (it.length > RecoveryTextLimits.RESULT_WIRE_CHARS) malformed()
        }
    }

    fun decode(wire: String, expected: RecoveryReplayRef): RecoveryResult {
        if (wire.length > RecoveryTextLimits.RESULT_WIRE_CHARS || !wire.startsWith(PREFIX)) malformed()
        try {
            val encoded = wire.substring(PREFIX.length)
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size !in FIXED_BYTES..RecoveryTextLimits.RESULT_BYTES ||
                Base64.getEncoder().encodeToString(bytes) != encoded) malformed()
            val input = ByteBuffer.wrap(bytes)
            if (input.long != MAGIC || input.short.toInt() != 1) malformed()
            val slot = input.int
            val store = getUuid(input)
            val generation = input.long
            val attempt = getUuid(input)
            val digest = RecoveryContextDigest.of(ByteArray(RecoveryContextDigest.BYTES).also(input::get))
            val ref = expected.record
            if (slot != ref.slot || store != ref.storeId || generation != ref.identity.generation ||
                attempt != expected.attempt || digest != ref.identity.context) malformed()
            val first = input.long
            val count = input.long
            val footer = when (input.get().toInt()) { 0 -> false; 1 -> true; else -> malformed() }
            val fields = List(5) { getString(input, RecoveryTextLimits.MODEL_FIELD_CODE_UNITS) }
            val raw = getString(input, RecoveryTextLimits.RAW_CODE_UNITS)
            if (input.hasRemaining()) malformed()
            val model = RecoveryModelIdentity(fields[0], fields[1], fields[2], fields[3], fields[4])
            if (!model.isCompatible()) malformed()
            return RecoveryResult(expected, first, count, footer, model, raw).also(::validate)
        } catch (_: Exception) { malformed() }
    }

    fun modelIdentity(model: RecoveryModelIdentity): String {
        val fields = modelFields(model).take(4)
        if (fields.any { it.isBlank() || it.length > RecoveryTextLimits.MODEL_FIELD_CODE_UNITS }) malformed()
        val out = ByteBuffer.allocate(16 + fields.sumOf { it.length * 2 })
        fields.forEach { putString(out, it) }
        return ("EWR-MODEL-1:" + Base64.getEncoder().encodeToString(out.array())).also {
            if (it.length > RecoveryTextLimits.MODEL_IDENTITY_CODE_UNITS) malformed()
        }
    }

    private fun validate(result: RecoveryResult) {
        val ref = result.replay.record
        if (ref.slot !in 0 until RecoveryLedger.SLOT_COUNT || ref.identity.generation <= 0L ||
            result.firstSample !in 0..RecoveryFrameLimits.MAX_SAMPLES || result.sampleCount <= 0L ||
            result.sampleCount > RecoveryFrameLimits.MAX_SAMPLES - result.firstSample ||
            result.rawText.length > RecoveryTextLimits.RAW_CODE_UNITS ||
            modelFields(result.model).any { it.isBlank() || it.length > RecoveryTextLimits.MODEL_FIELD_CODE_UNITS }) malformed()
    }

    private fun modelFields(model: RecoveryModelIdentity): List<String> =
        listOf(model.engineId, model.modelId, model.pinnedRevision, model.recipeId, model.displayName)

    private fun putString(out: ByteBuffer, value: String) {
        out.putInt(value.length)
        value.forEach { out.putChar(it) }
    }

    private fun getString(input: ByteBuffer, maxUnits: Int): String {
        val count = input.int
        if (count !in 0..maxUnits || count > input.remaining() / 2) malformed()
        return String(CharArray(count) { input.char })
    }

    private fun putUuid(out: ByteBuffer, value: UUID) { out.putLong(value.mostSignificantBits); out.putLong(value.leastSignificantBits) }
    private fun getUuid(input: ByteBuffer): UUID = UUID(input.long, input.long)
    private fun malformed(): Nothing = throw RecoveryResultException()
}
