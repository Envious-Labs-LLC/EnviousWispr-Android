package com.envi.wispr.recovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32
import java.nio.ByteBuffer

/** Bounded app-private allocator metadata. CRC32 detects accidental corruption; it is not authentication. */
internal object RecoveryLedgerCodec {
    private const val MAGIC = 0x4557524c45443031L // EWRLED01
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 4
    private const val MIN_BYTES = 40
    const val MAX_BYTES = 208

    fun encode(ledger: RecoveryLedger): ByteArray {
        val body = ByteArrayOutputStream(MAX_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeLong(MAGIC)
                output.writeShort(VERSION)
                output.uuid(ledger.storeId)
                output.writeLong(ledger.nextGeneration)
                ledger.slots.forEach { record ->
                    output.writeBoolean(record != null)
                    if (record != null) {
                        output.uuid(record.identity.takeId)
                        output.writeLong(record.identity.generation)
                        output.write(record.identity.context.copyBytes())
                        output.writeLong(record.createdAtMs)
                        output.writeByte(record.keyStage.wireTag)
                        output.writeByte(record.phase.wireTag)
                        output.writeByte(record.disposition.wireTag)
                        output.writeBoolean(record.attempt != null)
                        record.attempt?.let { output.uuid(it) }
                    }
                }
            }
            bytes.toByteArray()
        }
        if (body.size + CHECKSUM_BYTES !in MIN_BYTES..MAX_BYTES) malformed()
        return body + ByteBuffer.allocate(CHECKSUM_BYTES).putInt(checksum(body)).array()
    }

    fun decode(encoded: ByteArray): RecoveryLedger {
        if (encoded.size !in MIN_BYTES..MAX_BYTES) malformed()
        val body = encoded.copyOfRange(0, encoded.size - CHECKSUM_BYTES)
        if (checksum(body) != ByteBuffer.wrap(encoded, body.size, CHECKSUM_BYTES).int) malformed()
        try {
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                if (input.readLong() != MAGIC || input.readUnsignedShort() != VERSION) {
                    throw RecoveryStoreException(RecoveryStoreFailure.UNSUPPORTED_FORMAT)
                }
                val storeId = input.uuid()
                val nextGeneration = input.readLong()
                val slots = List(RecoveryLedger.SLOT_COUNT) {
                    if (!input.boolean()) null else {
                        val takeId = input.uuid()
                        val generation = input.readLong()
                        val digest = RecoveryContextDigest.of(ByteArray(32).also(input::readFully))
                        val createdAtMs = input.readLong()
                        val keyTag = input.readUnsignedByte()
                        val keyStage = RecoveryKeyStage.entries.singleOrNull { it.wireTag == keyTag } ?: malformed()
                        val phaseTag = input.readUnsignedByte()
                        val phase = RecoveryRecordPhase.entries.singleOrNull { it.wireTag == phaseTag } ?: malformed()
                        val dispositionTag = input.readUnsignedByte()
                        val disposition = RecoveryDisposition.entries.singleOrNull { it.wireTag == dispositionTag } ?: malformed()
                        val attempt = if (input.boolean()) input.uuid() else null
                        RecoveryRecord(RecoveryRecordIdentity(takeId, generation, digest), createdAtMs, keyStage, phase, disposition, attempt)
                    }
                }
                if (input.read() != -1) malformed()
                return RecoveryLedger.of(storeId, nextGeneration, slots)
            }
        } catch (failure: RecoveryStoreException) {
            throw failure
        } catch (_: IOException) {
            malformed()
        }
    }

    private fun DataOutputStream.uuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.uuid(): UUID = UUID(readLong(), readLong())

    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> malformed()
    }

    private fun checksum(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

    private fun malformed(): Nothing = throw RecoveryStoreException(RecoveryStoreFailure.MALFORMED)
}
