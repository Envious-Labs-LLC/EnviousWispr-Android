package com.envi.wispr.history

import com.envi.wispr.audio.PcmAudio
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.recovery.RecoveryFrameLimits
import com.envi.wispr.recovery.RecoveryTextLimits
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

internal data class RecoveredWordsRecord(val transcript: TranscriptEntity, val sampleCount: Long)
internal class RecoveryWordsException : IOException("Recovered words unavailable")

/** Versioned produced text, not audio or settings. CRC is accidental-corruption detection, not authentication. */
internal object RecoveryWordsCodec {
    private val magic = byteArrayOf(0x45, 0x57, 0x52, 0x57, 0x52, 0x44, 0x30, 0x31) // EWRWRD01
    private const val FIXED_BYTES = 82

    fun encode(record: RecoveredWordsRecord): ByteArray {
        val take = validate(record)
        val row = record.transcript
        val strings = listOf(row.speechEngine, requireNotNull(row.recoveryModelIdentity), row.originalText, row.finalText)
        val bytes = FIXED_BYTES.toLong() + (if (row.asrMs == null) 0L else 8L) + strings.sumOf { it.length.toLong() * 2L }
        if (bytes > RecoveryTextLimits.WORDS_BYTES) malformed()
        val output = ByteBuffer.allocate(bytes.toInt())
        output.put(magic); output.putShort(1)
        output.putLong(take.mostSignificantBits); output.putLong(take.leastSignificantBits)
        output.putLong(row.createdAtMs); output.putLong(row.durationMs)
        output.putLong(requireNotNull(row.recoveryStartSample)); output.putLong(record.sampleCount)
        output.put((if (row.recoveryPartial == true) 1 else 0).toByte())
        output.put(0.toByte()) // Deterministic processing only; never AI polish.
        output.put(0.toByte()) // History only; never clipboard/editor insertion.
        val asr = row.asrMs
        output.put((if (asr == null) 0 else 1).toByte())
        if (asr != null) output.putLong(asr)
        strings.forEach { value ->
            output.putInt(value.length)
            value.forEach { output.putChar(it) }
        }
        output.putInt(CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt())
        if (output.hasRemaining()) malformed()
        return output.array()
    }

    fun decode(bytes: ByteArray, expectedTakeId: String): RecoveredWordsRecord {
        if (bytes.size !in FIXED_BYTES..RecoveryTextLimits.WORDS_BYTES) malformed()
        val bodySize = bytes.size - 4
        if (CRC32().apply { update(bytes, 0, bodySize) }.value.toInt() != ByteBuffer.wrap(bytes, bodySize, 4).int) malformed()
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes, 0, bodySize))
            if (!ByteArray(8).also(input::readFully).contentEquals(magic) || input.readUnsignedShort() != 1) malformed()
            val take = UUID(input.readLong(), input.readLong()).toString()
            if (take != expectedTakeId) malformed()
            val created = input.readLong()
            val duration = input.readLong()
            val first = input.readLong()
            val samples = input.readLong()
            val partial = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> malformed() }
            if (input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) malformed()
            val asr = when (input.readUnsignedByte()) { 0 -> null; 1 -> input.readLong(); else -> malformed() }
            val engine = readString(input, RecoveryTextLimits.MODEL_FIELD_CODE_UNITS)
            val model = readString(input, RecoveryTextLimits.MODEL_IDENTITY_CODE_UNITS)
            val raw = readString(input, RecoveryTextLimits.RAW_CODE_UNITS)
            val final = readString(input, RecoveryTextLimits.WORDS_BYTES / 2)
            if (input.read() != -1) malformed()
            val record = RecoveredWordsRecord(TranscriptEntity(
                originalText = raw, finalText = final, createdAtMs = created, durationMs = duration,
                speechEngine = engine, polishEngine = PolishEngineLabels.OFF, polishLatencyMs = 0L,
                insertionResult = InsertionResults.HISTORY_ONLY, recovered = true, takeId = take,
                asrMs = asr, recoveryStartSample = first, recoveryPartial = partial, recoveryModelIdentity = model,
            ), samples)
            validate(record)
            return record
        } catch (_: Exception) { malformed() }
    }

    private fun validate(record: RecoveredWordsRecord): UUID {
        val row = record.transcript
        val takeId = row.takeId ?: malformed()
        val take = try { UUID.fromString(takeId) } catch (_: IllegalArgumentException) { malformed() }
        if (take.toString() != takeId || !row.recovered || row.interrupted || row.status != TranscriptEntity.STATUS_COMPLETED ||
            row.insertionResult != InsertionResults.HISTORY_ONLY || row.polishEngine != PolishEngineLabels.OFF || row.polishLatencyMs != 0L
        ) malformed()
        val first = row.recoveryStartSample ?: malformed()
        if (row.recoveryPartial == null || first < 0L || first > RecoveryFrameLimits.MAX_SAMPLES || record.sampleCount <= 0L ||
            record.sampleCount > RecoveryFrameLimits.MAX_SAMPLES - first ||
            row.durationMs != record.sampleCount * 1_000L / PcmAudio.SAMPLE_RATE ||
            (first != 0L && !row.recoveryPartial) || row.asrMs?.let { it < 0L } == true
        ) malformed()
        if (row.speechEngine.isBlank() || row.speechEngine.length > RecoveryTextLimits.MODEL_FIELD_CODE_UNITS ||
            row.recoveryModelIdentity.isNullOrBlank() || row.recoveryModelIdentity.length > RecoveryTextLimits.MODEL_IDENTITY_CODE_UNITS ||
            row.originalText.length > RecoveryTextLimits.RAW_CODE_UNITS || row.finalText.isBlank()
        ) malformed()
        return take
    }

    private fun readString(input: DataInputStream, limit: Int): String {
        val units = input.readInt()
        // This input is a bounded in-memory envelope; available is its exact remaining byte count.
        if (units < 0 || units > limit || units.toLong() * 2L > input.available()) malformed()
        return String(CharArray(units) { input.readUnsignedShort().toChar() })
    }

    private fun malformed(): Nothing = throw RecoveryWordsException()
}
