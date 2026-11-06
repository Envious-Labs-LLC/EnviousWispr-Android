package com.envi.wispr.recovery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal sealed interface RecoveryStoreResult<out T> {
    data class Ready<T>(val value: T) : RecoveryStoreResult<T>
    data object Busy : RecoveryStoreResult<Nothing>
    data class Unavailable(val reason: RecoveryStoreFailure) : RecoveryStoreResult<Nothing>
}

internal data class RecoveryRecordRef(val storeId: UUID, val slot: Int, val identity: RecoveryRecordIdentity)

/** Allocator disposition only, never a key-service or negative-census health verdict. */
internal enum class RecoveryAllocationState { NOT_QUARANTINED, QUARANTINED }
internal data class RecoveryStoreSnapshot(val ledger: RecoveryLedger, val allocation: RecoveryAllocationState)

internal enum class RecoveryReservationClose { REQUESTED, RELEASED, UNCERTAIN }

/**
 * Recovery-only allocation/key ownership. Every method is blocking and must run on an owning IO
 * worker, never UI/capture. No session, model, database, editor or idle worker lives here.
 */
internal class RecoveryAudioStore(context: Context) {
    private val files = RecoveryStoreFiles(context)

    fun reserveOnWorker(context: FrozenRecoveryContext): RecoveryStoreResult<Reservation> {
        var main: RecoveryFileClaim? = null
        var transferred = false
        var encoded: ByteArray? = null
        try {
            encoded = RecoveryContextCodec.encode(context)
            val digest = RecoveryContextCodec.digest(encoded)
            ensureInitialized()
            val result = withLedger { ledger ->
                if (files.isQuarantined()) fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
                if (ledger.nextGeneration == Long.MAX_VALUE || ledger.slots.any { it?.identity?.takeId == context.takeId }) fail(RecoveryStoreFailure.CAPACITY)
                val slot = ledger.slots.indexOfFirst { it == null }
                if (slot == -1) fail(RecoveryStoreFailure.CAPACITY)
                main = acquire(ownerNames(slot).first())
                if (files.hasRecordFiles(slot)) fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
                val identity = RecoveryRecordIdentity(context.takeId, ledger.nextGeneration, digest)
                val record = RecoveryRecord(identity, context.createdAtMs, RecoveryKeyStage.NOT_ISSUED, RecoveryRecordPhase.RESERVED, RecoveryDisposition.NONE, null)
                files.writeLedger(RecoveryLedger.of(ledger.storeId, ledger.nextGeneration + 1L, ledger.slots.mapIndexed { index, old -> if (index == slot) record else old }))
                RecoveryRecordRef(ledger.storeId, slot, identity)
            }
            return when (result) {
                is RecoveryStoreResult.Ready -> {
                    val reservation = Reservation(this, result.value, checkNotNull(main), encoded)
                    transferred = true
                    RecoveryStoreResult.Ready(reservation)
                }
                RecoveryStoreResult.Busy -> RecoveryStoreResult.Busy
                is RecoveryStoreResult.Unavailable -> result
            }
        } catch (_: StoreBusy) {
            return RecoveryStoreResult.Busy
        } catch (error: RecoveryStoreException) {
            return RecoveryStoreResult.Unavailable(error.reason)
        } catch (_: Exception) {
            return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.IO_UNAVAILABLE)
        } finally {
            if (!transferred) {
                main?.release()
                encoded?.fill(0)
            }
        }
    }

    /** Metadata only, not encrypted-envelope validation or Delete all authorization. Missing is never empty. */
    fun snapshotOnWorker(): RecoveryStoreResult<RecoveryStoreSnapshot> = withLedger {
        RecoveryStoreSnapshot(it, if (files.isQuarantined()) RecoveryAllocationState.QUARANTINED else RecoveryAllocationState.NOT_QUARANTINED)
    }

    /** Optional attachment work only. The capture loop never calls the store or waits for this. */
    fun openAudioWriterOnWorker(ref: RecoveryRecordRef): RecoveryStoreResult<AudioWriterLease> {
        var claim: RecoveryFileClaim? = null
        var channel: FileChannel? = null
        var transferred = false
        try {
            val admission = withLedger { ledger ->
                val current = record(ledger, ref)
                if (current.phase != RecoveryRecordPhase.ARMABLE || current.keyStage != RecoveryKeyStage.SETTLED) fail(RecoveryStoreFailure.INELIGIBLE)
                claim = acquire(ownerNames(ref.slot)[1])
                replace(ledger, ref, current.copy(phase = RecoveryRecordPhase.WRITING))
            }
            when (admission) {
                RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
                is RecoveryStoreResult.Unavailable -> return admission
                is RecoveryStoreResult.Ready -> Unit
            }
            val key = keyStore().getKey(RecoveryKeyAliases.of(ref), null) as? SecretKey
                ?: return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
            channel = files.createAudio(ref.slot)
            return RecoveryStoreResult.Ready(AudioWriterLease(ref.identity, key, channel, checkNotNull(claim))).also { transferred = true }
        } catch (error: RecoveryStoreException) {
            if (error.reason == RecoveryStoreFailure.IO_UNAVAILABLE) claim?.poisonOnWorker()
            return RecoveryStoreResult.Unavailable(error.reason)
        } catch (_: Exception) {
            return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
        } finally {
            if (!transferred) {
                try { channel?.close() } catch (_: Throwable) { claim?.poisonOnWorker() }
                claim?.release()
            }
        }
    }

    /** One writer worker owns this lease from creation through actual final close. */
    internal class AudioWriterLease internal constructor(
        private val identity: RecoveryRecordIdentity,
        private val key: SecretKey,
        private val channel: FileChannel,
        private val claim: RecoveryFileClaim,
    ) {
        private var closed: RecoveryClaimRelease? = null
        private var frames = 0L
        var firstSample: Long? = null
            private set
        var sampleCount: Int = 0
            private set

        fun appendOnWorker(startSample: Long, pcm: ByteArray) {
            if (startSample != (firstSample?.plus(sampleCount) ?: startSample)) {
                throw RecoveryFrameException(RecoveryFrameFailure.MALFORMED)
            }
            val frame = RecoveryFrameCodec.seal(
                RecoveryFrameHeader(identity, RecoveryFrameKind.AUDIO, frames, startSample, pcm.size / 2), pcm, key,
            )
            writeOnWorker(frame)
            if (firstSample == null) firstSample = startSample
            sampleCount += pcm.size / 2
            frames++
        }

        fun completeOnWorker(endSample: Long): Boolean {
            if (frames == 0L || firstSample?.plus(sampleCount) != endSample) return false
            writeOnWorker(RecoveryFrameCodec.seal(
                RecoveryFrameHeader(identity, RecoveryFrameKind.COMPLETED_CAPTURE, frames, endSample, sampleCount), ByteArray(0), key,
            ))
            return true
        }

        private fun writeOnWorker(frame: ByteArray) {
            check(closed == null)
            try {
                val buffer = ByteBuffer.wrap(frame)
                while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw RecoveryStoreException(RecoveryStoreFailure.IO_UNAVAILABLE)
                channel.force(true)
            } catch (_: Exception) {
                claim.poisonOnWorker()
                throw RecoveryStoreException(RecoveryStoreFailure.IO_UNAVAILABLE)
            }
        }

        fun closeOnWorker(): RecoveryClaimRelease {
            closed?.let { return it }
            try { channel.close() } catch (_: Throwable) { claim.poisonOnWorker() }
            return claim.release().also { closed = it }
        }
    }

    fun markTerminalOnWorker(ref: RecoveryRecordRef, disposition: RecoveryDisposition): RecoveryStoreResult<Unit> = withLedger { ledger ->
        if (disposition == RecoveryDisposition.NONE) fail(RecoveryStoreFailure.INELIGIBLE)
        val current = record(ledger, ref)
        // A committed discard cannot be weakened by a late ordinary ending or text save.
        val chosen = if (current.disposition == RecoveryDisposition.DISCARDED) RecoveryDisposition.DISCARDED else disposition
        replace(ledger, ref, current.copy(phase = RecoveryRecordPhase.TERMINAL, disposition = chosen))
    }

    /** No inference from missing keys or alias enumeration; uncertain creation remains capacity-bearing. */
    fun cleanupOnWorker(ref: RecoveryRecordRef): RecoveryStoreResult<Unit> {
        val claims = ArrayList<RecoveryFileClaim>()
        var uncertain = false
        val result = try {
            cleanupClaimedOnWorker(ref, claims)
        } catch (error: RecoveryStoreException) {
            if (error.reason == RecoveryStoreFailure.IO_UNAVAILABLE) claims.forEach { it.poisonOnWorker() }
            RecoveryStoreResult.Unavailable(error.reason)
        } finally {
            claims.asReversed().forEach { if (it.release() != RecoveryClaimRelease.RELEASED) uncertain = true }
        }
        return if (uncertain) RecoveryStoreResult.Unavailable(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN) else result
    }

    private fun cleanupClaimedOnWorker(ref: RecoveryRecordRef, claims: MutableList<RecoveryFileClaim>): RecoveryStoreResult<Unit> {
        val checked = withLedger { ledger ->
            val current = record(ledger, ref)
            if (current.phase != RecoveryRecordPhase.TERMINAL) fail(RecoveryStoreFailure.INELIGIBLE)
            ownerNames(ref.slot).forEach { claims.add(acquire(it)) }
            if (current.keyStage == RecoveryKeyStage.CREATE_MAY_HAVE_RUN) fail(RecoveryStoreFailure.KEY_UNAVAILABLE)
            current.keyStage
        }
        when (checked) {
            RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
            is RecoveryStoreResult.Unavailable -> return checked
            is RecoveryStoreResult.Ready -> when (checked.value) {
                RecoveryKeyStage.CREATE_MAY_HAVE_RUN -> return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
                RecoveryKeyStage.NOT_ISSUED, RecoveryKeyStage.DELETE_ACKNOWLEDGED -> Unit
                RecoveryKeyStage.SETTLED -> {
                    try {
                        keyStore().deleteEntry(RecoveryKeyAliases.of(ref))
                    } catch (_: Exception) {
                        return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
                    }
                    val receipt = settleLedger { ledger ->
                        val current = record(ledger, ref)
                        replace(ledger, ref, current.copy(keyStage = RecoveryKeyStage.DELETE_ACKNOWLEDGED))
                    }
                    when (receipt) {
                        is RecoveryStoreResult.Ready -> Unit
                        RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
                        is RecoveryStoreResult.Unavailable -> return receipt
                    }
                }
            }
        }
        files.removeRecordFiles(ref.slot)
        // MAIN remains held until auxiliary release is known, so an empty slot cannot race an
        // uncertain old AUDIO/ASR owner. The outer release loop is deliberately idempotent.
        var auxiliaryUncertain = false
        claims.drop(1).asReversed().forEach {
            if (it.release() != RecoveryClaimRelease.RELEASED) auxiliaryUncertain = true
        }
        if (auxiliaryUncertain) {
            claims.first().poisonOnWorker()
            return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
        }
        return withLedger { ledger ->
            val current = record(ledger, ref)
            if (current.phase != RecoveryRecordPhase.TERMINAL || current.keyStage !in setOf(RecoveryKeyStage.NOT_ISSUED, RecoveryKeyStage.DELETE_ACKNOWLEDGED)) fail(RecoveryStoreFailure.INELIGIBLE)
            files.writeLedger(RecoveryLedger.of(ledger.storeId, ledger.nextGeneration, ledger.slots.mapIndexed { index, old -> if (index == ref.slot) null else old }))
        }
    }

    internal class Reservation internal constructor(
        private val store: RecoveryAudioStore,
        val ref: RecoveryRecordRef,
        private val main: RecoveryFileClaim,
        private var context: ByteArray?,
    ) {
        private val started = AtomicBoolean()
        private val monitor = Any()
        private var active = false
        private var closing = false
        private var closed: RecoveryReservationClose? = null

        fun prepareOnWorker(): RecoveryStoreResult<Unit> {
            synchronized(monitor) {
                if (closing || !started.compareAndSet(false, true)) return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.INELIGIBLE)
                active = true
            }
            try {
                return store.prepareOnWorker(ref, main, checkNotNull(context)) { synchronized(monitor) { closing } }
            } finally {
                synchronized(monitor) {
                    active = false
                    context?.fill(0)
                    context = null
                    if (closing) release()
                }
            }
        }

        /** A request during a blocked creation retains the actual claim until that operation exits. */
        fun closeOnWorker(): RecoveryReservationClose = synchronized(monitor) {
            closing = true
            if (active) RecoveryReservationClose.REQUESTED else release()
        }

        private fun release(): RecoveryReservationClose {
            closed?.let { return it }
            context?.fill(0)
            context = null
            return when (main.release()) {
                RecoveryClaimRelease.RELEASED -> RecoveryReservationClose.RELEASED
                RecoveryClaimRelease.UNCERTAIN -> RecoveryReservationClose.UNCERTAIN
            }.also { closed = it }
        }
    }

    private fun prepareOnWorker(ref: RecoveryRecordRef, main: RecoveryFileClaim, context: ByteArray, closing: () -> Boolean): RecoveryStoreResult<Unit> {
        try {
            if (!main.matches(files, ownerNames(ref.slot).first()) || closing()) fail(RecoveryStoreFailure.INELIGIBLE)
            if (RecoveryContextCodec.digest(context) != ref.identity.context) fail(RecoveryStoreFailure.MALFORMED)
            when (val observed = inspectPositiveAliasesOnWorker()) {
                is RecoveryStoreResult.Ready -> Unit
                RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
                is RecoveryStoreResult.Unavailable -> return observed
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(RecoveryKeyAliases.of(ref), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            val authorization = withLedger { ledger ->
                val current = record(ledger, ref)
                if (files.isQuarantined() || closing() || current.phase != RecoveryRecordPhase.RESERVED || current.keyStage != RecoveryKeyStage.NOT_ISSUED) fail(RecoveryStoreFailure.INELIGIBLE)
                replace(ledger, ref, current.copy(keyStage = RecoveryKeyStage.CREATE_MAY_HAVE_RUN))
            }
            when (authorization) {
                RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
                is RecoveryStoreResult.Unavailable -> return authorization
                is RecoveryStoreResult.Ready -> Unit
            }
            // Sole invocation, only after a successful durable authorization. No missing-key fallback.
            val key = generator.generateKey()
            val settled = settleLedger { ledger ->
                val current = record(ledger, ref)
                replace(ledger, ref, current.copy(keyStage = RecoveryKeyStage.SETTLED))
            }
            when (settled) {
                RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
                is RecoveryStoreResult.Unavailable -> return settled
                is RecoveryStoreResult.Ready -> Unit
            }
            if (closing()) fail(RecoveryStoreFailure.INELIGIBLE)
            val frame = RecoveryFrameCodec.seal(RecoveryFrameHeader(ref.identity, RecoveryFrameKind.CONTEXT, 0L, 0L, 0), context, key)
            files.writeContext(ref.slot, frame)
            return withLedger { ledger ->
                val current = record(ledger, ref)
                if (closing() || current.phase != RecoveryRecordPhase.RESERVED || current.keyStage != RecoveryKeyStage.SETTLED) fail(RecoveryStoreFailure.INELIGIBLE)
                replace(ledger, ref, current.copy(phase = RecoveryRecordPhase.ARMABLE))
            }
        } catch (error: RecoveryStoreException) {
            if (error.reason == RecoveryStoreFailure.IO_UNAVAILABLE) main.poisonOnWorker()
            return RecoveryStoreResult.Unavailable(error.reason)
        } catch (_: Exception) {
            return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
        }
    }

    private fun inspectPositiveAliasesOnWorker(): RecoveryStoreResult<Unit> {
        val before = when (val answer = withLedger { it }) {
            is RecoveryStoreResult.Ready -> answer.value
            RecoveryStoreResult.Busy -> return RecoveryStoreResult.Busy
            is RecoveryStoreResult.Unavailable -> return answer
        }
        val positive = try {
            RecoveryKeyAliases.observe(keyStore().aliases())
        } catch (_: Exception) {
            RecoveryKeyAliases.failed()
        }
        val evidence = withLedger { current ->
            // A reservation, cleanup or state change during observation invalidates the comparison.
            // Never quarantine a legitimately retired key using a stale pre-delete observation.
            if (current.storeId != before.storeId || current.nextGeneration != before.nextGeneration || current.slots != before.slots) throw StoreBusy()
            val allowed = current.slots.mapIndexedNotNull { slot, record ->
                record?.takeIf { it.keyStage == RecoveryKeyStage.SETTLED || it.keyStage == RecoveryKeyStage.CREATE_MAY_HAVE_RUN }
                    ?.let { RecoveryKeyAliases.of(RecoveryRecordRef(current.storeId, slot, it.identity)) }
            }.toSet()
            if (positive.oversizedRecoveryAlias || positive.names.any { it !in allowed }) {
                files.quarantineAllocation()
                fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
            }
            if (files.isQuarantined()) fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
        }
        return when (evidence) {
            RecoveryStoreResult.Busy -> RecoveryStoreResult.Busy
            is RecoveryStoreResult.Unavailable -> evidence
            is RecoveryStoreResult.Ready -> when (positive.scan) {
                RecoveryKeyAliases.Scan.ENDED -> RecoveryStoreResult.Ready(Unit)
                RecoveryKeyAliases.Scan.CAPPED -> RecoveryStoreResult.Busy
                RecoveryKeyAliases.Scan.FAILED -> RecoveryStoreResult.Unavailable(RecoveryStoreFailure.KEY_UNAVAILABLE)
            }
        }
    }

    private fun ensureInitialized() {
        if (!initialization.tryLock()) throw StoreBusy()
        try {
            if (files.createRootIfAbsent()) {
                files.createInitialLockFiles()
                val claim = acquire(RecoveryLockName.STORE)
                try {
                    files.writeLedger(RecoveryLedger.of(UUID.randomUUID(), 1L, List(RecoveryLedger.SLOT_COUNT) { null }))
                } catch (error: RecoveryStoreException) {
                    if (error.reason == RecoveryStoreFailure.IO_UNAVAILABLE) claim.poisonOnWorker()
                    throw error
                } finally {
                    if (claim.release() != RecoveryClaimRelease.RELEASED) fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
                }
            } else if (!files.hasLedger()) fail(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
        } finally {
            initialization.unlock()
        }
    }

    private fun <T> withLedger(action: (RecoveryLedger) -> T): RecoveryStoreResult<T> = accessLedger(false, action)
    private fun <T> settleLedger(action: (RecoveryLedger) -> T): RecoveryStoreResult<T> = accessLedger(true, action)

    private fun <T> accessLedger(settlement: Boolean, action: (RecoveryLedger) -> T): RecoveryStoreResult<T> {
        // Only the initializer may open coordination before first ledger publication. A healthy
        // in-progress initialization must not be poisoned by a concurrent early snapshot.
        try {
            if (!files.hasLedger()) return RecoveryStoreResult.Unavailable(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
        } catch (error: RecoveryStoreException) {
            return RecoveryStoreResult.Unavailable(error.reason)
        }
        val acquisition = if (settlement) RecoveryFileClaim.acquireMetadataForSettlement(files) else RecoveryFileClaim.tryAcquire(files, RecoveryLockName.STORE)
        val claim = when (val acquired = acquisition) {
            RecoveryClaimResult.Busy -> return RecoveryStoreResult.Busy
            is RecoveryClaimResult.Unavailable -> return RecoveryStoreResult.Unavailable(acquired.reason)
            is RecoveryClaimResult.Acquired -> acquired.claim
        }
        var released = RecoveryClaimRelease.UNCERTAIN
        val answer = try {
            val ledger = files.readLedger()
            files.verifyAllocatorLayout(ledger)
            RecoveryStoreResult.Ready(action(ledger))
        } catch (_: StoreBusy) {
            RecoveryStoreResult.Busy
        } catch (error: RecoveryStoreException) {
            if (error.reason == RecoveryStoreFailure.IO_UNAVAILABLE) claim.poisonOnWorker()
            RecoveryStoreResult.Unavailable(error.reason)
        } catch (_: Exception) {
            RecoveryStoreResult.Unavailable(RecoveryStoreFailure.IO_UNAVAILABLE)
        } finally {
            released = claim.release()
        }
        return if (released == RecoveryClaimRelease.RELEASED) answer else RecoveryStoreResult.Unavailable(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN)
    }

    private fun record(ledger: RecoveryLedger, ref: RecoveryRecordRef): RecoveryRecord {
        if (ref.storeId != ledger.storeId || ref.slot !in ledger.slots.indices) fail(RecoveryStoreFailure.STALE_IDENTITY)
        return ledger.slots[ref.slot]?.takeIf { it.identity == ref.identity } ?: fail(RecoveryStoreFailure.STALE_IDENTITY)
    }

    private fun replace(ledger: RecoveryLedger, ref: RecoveryRecordRef, value: RecoveryRecord) {
        files.writeLedger(RecoveryLedger.of(ledger.storeId, ledger.nextGeneration, ledger.slots.mapIndexed { index, old -> if (index == ref.slot) value else old }))
    }

    private fun acquire(name: RecoveryLockName): RecoveryFileClaim = when (val answer = RecoveryFileClaim.tryAcquire(files, name)) {
        is RecoveryClaimResult.Acquired -> answer.claim
        RecoveryClaimResult.Busy -> throw StoreBusy()
        is RecoveryClaimResult.Unavailable -> fail(answer.reason)
    }

    private fun ownerNames(slot: Int): List<RecoveryLockName> = when (slot) {
        0 -> listOf(RecoveryLockName.MAIN_0, RecoveryLockName.AUDIO_0, RecoveryLockName.ASR_0)
        1 -> listOf(RecoveryLockName.MAIN_1, RecoveryLockName.AUDIO_1, RecoveryLockName.ASR_1)
        else -> fail(RecoveryStoreFailure.MALFORMED)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun fail(reason: RecoveryStoreFailure): Nothing = throw RecoveryStoreException(reason)
    private class StoreBusy : Exception()

    companion object {
        private val initialization = ReentrantLock()
    }
}
