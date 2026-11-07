package com.envi.wispr.history

import android.content.Context
import com.envi.wispr.recovery.*
import com.envi.wispr.ui.RecordingProtectionOwner
import com.envi.wispr.ui.RescuedWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Existing History transactions own deletion; protected identities extend their snapshot and cleanup. */
internal class ProtectedHistoryDeletion(
    context: Context,
    private val repository: TranscriptRepository,
    private val words: RescuedWords,
    private val recordings: RecordingProtectionOwner,
    private val audio: AudioHistoryRecovery,
) {
    private val store = RecoveryAudioStore(context.applicationContext)

    suspend fun delete(row: TranscriptEntity) = withContext(Dispatchers.IO) {
        words.deleting(row.takeId) { repository.delete(row) }
        row.takeId?.let { id -> canonical(id)?.let { committed(setOf(it)) } }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        // Authenticate orphan envelopes before the DB transaction; no provider call under its write lock.
        val verified = verifiedIdentities()
        val deleted = words.clearingWithSnapshot { wordSnapshot ->
            repository.deleteAllWithSnapshot { rowIds ->
                // Room owns this snapshot point: new row writers cannot commit before deletion finishes.
                val live = recordings.pendingTakeIds().map(UUID::toString).toSet() +
                    audio.authenticatedRefs().map { it.identity.takeId.toString() }
                val known = verified + rowIds + live
                val latest = snapshot() // Metadata only, nonblocking. A new unknown occupant refuses/rolls back.
                latest?.ledger?.slots?.filterNotNull()?.forEach {
                    if (it.identity.takeId.toString() !in known) throw HistoryDeletionUnavailable()
                }
                wordSnapshot() + known
            }
        }
        committed(deleted.mapNotNull(::canonical).toSet())
    }

    private fun verifiedIdentities(): Set<String> {
        val known = (recordings.pendingTakeIds().map(UUID::toString) +
            audio.authenticatedRefs().map { it.identity.takeId.toString() }).toMutableSet()
        val initial = snapshot() ?: return known
        initial.ledger.slots.forEachIndexed { slot, record ->
            if (record != null && record.identity.takeId.toString() !in known) {
                val ref = RecoveryRecordRef(initial.ledger.storeId, slot, record.identity)
                if (record.phase == RecoveryRecordPhase.TERMINAL && store.cleanupOnWorker(ref) is RecoveryStoreResult.Ready) return@forEachIndexed
                when (val proof = store.authenticateTakeOnWorker(ref)) {
                    is RecoveryStoreResult.Ready -> known += proof.value.toString()
                    RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> throw HistoryDeletionUnavailable()
                }
            }
        }
        return known
    }

    private fun snapshot(): RecoveryStoreSnapshot? = when (val answer = store.snapshotIfPresentOnWorker()) {
        is RecoveryStoreResult.Ready -> answer.value.also {
            if (it?.allocation == RecoveryAllocationState.QUARANTINED) throw HistoryDeletionUnavailable()
        }
        RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> throw HistoryDeletionUnavailable()
    }

    private fun committed(ids: Set<UUID>) {
        recordings.discardCommitted(ids)
        audio.deleted(ids)
        // A confirmed DB deletion suppresses every late save/replay even if physical cleanup defers.
        val current = when (val answer = store.snapshotOnWorker()) {
            is RecoveryStoreResult.Ready -> answer.value
            RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> return
        }
        current.ledger.slots.forEachIndexed { slot, record ->
            if (record != null && record.identity.takeId in ids) {
                val ref = RecoveryRecordRef(current.ledger.storeId, slot, record.identity)
                if (store.markTerminalOnWorker(ref, RecoveryDisposition.DISCARDED) is RecoveryStoreResult.Ready &&
                    store.cleanupOnWorker(ref) is RecoveryStoreResult.Ready) recordings.forgetDisposed(ref)
            }
        }
    }

    private fun canonical(value: String): UUID? = runCatching {
        UUID.fromString(value).takeIf { it.toString() == value }
    }.getOrNull()
}

internal class HistoryDeletionUnavailable : IllegalStateException("Couldn't delete history. Please try again.")
