package com.envi.wispr.ui

import android.content.Context
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.history.HistoryWriteQueue
import com.envi.wispr.history.RecoveryDiscardOutcome
import com.envi.wispr.recovery.FrozenRecoveryContext
import com.envi.wispr.recovery.RecoveryAudioStore
import com.envi.wispr.recovery.RecoveryDisposition
import com.envi.wispr.recovery.RecoveryRecordRef
import com.envi.wispr.recovery.RecoveryStoreResult
import com.envi.wispr.vocabulary.CustomTerm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned capture protection. It owns no recorder, decoder, editor or retry timer. */
internal class RecordingProtectionOwner(
    context: Context,
    private val writes: HistoryWriteQueue,
    private val scope: CoroutineScope,
) {
    private val store = RecoveryAudioStore(context.applicationContext)
    private val preparing = AtomicBoolean()
    private val pending = ConcurrentHashMap<UUID, RecordingProtection>()

    fun begin(takeId: String, createdAtMs: Long): RecordingProtection =
        RecordingProtection(UUID.fromString(takeId), createdAtMs)

    /** Includes an arming take before it has a persistent slot, for the existing Delete all snapshot. */
    fun pendingTakeIds(): Set<UUID> = pending.keys.toSet()

    internal inner class RecordingProtection internal constructor(
        private val takeId: UUID,
        private val createdAtMs: Long,
    ) {
        private val lock = Any()
        private var started = false
        private var closed = false
        private var cancelled = false
        private var ending: Deferred<Boolean>? = null
        private val prepared = CompletableDeferred<Unit>()
        private val durableWords = CompletableDeferred<Unit>()
        private var reservation: RecoveryAudioStore.Reservation? = null
        private var discard: Deferred<RecoveryDiscardOutcome>? = null

        val potentiallyProtected: Boolean get() = synchronized(lock) { started }
        val cancelRequested: Boolean get() = synchronized(lock) { cancelled }

        fun observeWords(saved: SaveSlot, rescue: Deferred<RescueOutcome>?) =
            observeDurableWords(saved, rescue, durableWords)

        /** Admission is immediate; a blocked provider gets no queued successor preparation. */
        fun arm(cleanup: CleanupOptions, effectiveTerms: List<CustomTerm>, attach: (RecoveryRecordRef) -> Unit) {
            synchronized(lock) {
                if (closed || started || !preparing.compareAndSet(false, true)) return
                started = true
                pending[takeId] = this
            }
            scope.launch(Dispatchers.IO) {
                try {
                    if (synchronized(lock) { closed }) return@launch
                    val context = FrozenRecoveryContext.freeze(
                        takeId, createdAtMs, com.envi.wispr.asr.ParakeetEngine.RECIPE_ID,
                        "android-pcm-s16le-16000-mono-v1", cleanup, effectiveTerms,
                    )
                    val held = when (val answer = store.reserveOnWorker(context)) {
                        is RecoveryStoreResult.Ready -> answer.value
                        RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> return@launch
                    }
                    reservation = held
                    if (synchronized(lock) { closed }) return@launch
                    when (held.prepareOnWorker()) {
                        is RecoveryStoreResult.Ready -> if (!synchronized(lock) { closed }) attach(held.ref)
                        RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> Unit
                    }
                } catch (_: Exception) {
                    // Protection is optional. Ordinary capture and its errors retain their existing owners.
                } finally {
                    preparing.set(false)
                    prepared.complete(Unit)
                }
            }
        }

        /** Called when Cancel wins its reservation, before any later arming callback can publish. */
        fun cancel() {
            synchronized(lock) {
                cancelled = true
                closed = true
                if (started && discard == null) discard = writes.discardTakeForGood(takeId.toString())
            }
            finish(RecoveryDisposition.DISCARDED, cleanup = true)
        }

        /** Seal first, then settle on the application worker, which survives recorder-service teardown. */
        private fun finish(disposition: RecoveryDisposition, cleanup: Boolean, requiresWords: Boolean = false): Deferred<Boolean> = synchronized(lock) {
            closed = true
            ending?.let { return@synchronized it }
            if (!started) return@synchronized CompletableDeferred(true).also { ending = it }
            scope.async(Dispatchers.IO) {
                prepared.await()
                try {
                    val held = reservation ?: return@async true
                    // COMPLETED decides delivery, not persistence. Keep replay eligible until actual words survive.
                    if (requiresWords) durableWords.await()
                    val result = store.markTerminalOnWorker(held.ref, disposition)
                    // End the live main claim even if the disposition could not be written.
                    held.closeOnWorker()
                    if (cleanup && result is RecoveryStoreResult.Ready) store.cleanupOnWorker(held.ref)
                    result is RecoveryStoreResult.Ready
                } catch (_: Exception) {
                    runCatching { reservation?.closeOnWorker() }
                    false
                } finally {
                    reservation = null
                    pending.remove(takeId, this@RecordingProtection)
                }
            }.also { ending = it }
        }

        /** The terminal vocabulary is closed; adding a reason requires choosing its disposition here. */
        fun ended(reason: TerminalReason) {
            when (reason) {
                TerminalReason.CANCELLED_STARTING, TerminalReason.CANCELLED_RECORDING,
                TerminalReason.CANCELLED_PROCESSING, TerminalReason.INTERRUPTED_CANCELLING,
                TerminalReason.CAPTURE_CLOSE_UNSAFE_ON_CANCEL -> cancel()
                TerminalReason.COMPLETED -> finish(RecoveryDisposition.NO_REPLAY, cleanup = true, requiresWords = true)
                TerminalReason.NO_SPEECH, TerminalReason.ASR_EMPTY_DESPITE_AUDIO,
                TerminalReason.ASR_EMPTY_UNMEASURED, TerminalReason.FINAL_TEXT_EMPTY,
                TerminalReason.INTERRUPTED_STARTING, TerminalReason.INTERRUPTED_RECORDING,
                TerminalReason.INTERRUPTED_PROCESSING, TerminalReason.AUDIO_PROCESS_DIED,
                TerminalReason.AUDIO_PROCESS_UNRESPONSIVE, TerminalReason.ASR_PROCESS_DIED,
                TerminalReason.ASR_PROCESS_UNRESPONSIVE, TerminalReason.AUDIO_BIND_FAILED,
                TerminalReason.ASR_BIND_FAILED, TerminalReason.CAPTURE_START_NO_MICROPHONE,
                TerminalReason.CAPTURE_START_EARBUDS_REFUSED, TerminalReason.CAPTURE_START_FAILED,
                TerminalReason.START_EXCEPTION, TerminalReason.CAPTURE_ENDED_BEFORE_LIVE,
                TerminalReason.LIVE_WAIT_DEADLINE, TerminalReason.CAPTURE_FAILED_MID_TAKE,
                TerminalReason.CAPTURE_STILL_RUNNING_AFTER_STOP, TerminalReason.CAPTURE_CLOSE_UNSAFE,
                TerminalReason.AUDIO_FILE_MISSING, TerminalReason.ASR_NOT_READY,
                TerminalReason.ASR_FAILED, TerminalReason.ASR_CALLBACK_EXCEPTION ->
                    finish(if (cancelRequested) RecoveryDisposition.DISCARDED else RecoveryDisposition.NO_REPLAY, cleanup = true)
            }
        }

        /** A database commit suppresses replay even when a separate store-revocation write failed. */
        suspend fun awaitConfirmedEnding(): Boolean {
            val cancelledWrite = synchronized(lock) { discard }
            if (cancelledWrite != null) return cancelledWrite.await() == RecoveryDiscardOutcome.Committed
            return synchronized(lock) { ending }?.await() ?: true
        }
    }
}
