package com.envi.wispr.history

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.envi.wispr.asr.AsrBounds
import com.envi.wispr.asr.ParakeetEngine
import com.envi.wispr.audio.RecordingLimits
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelSwapGate
import com.envi.wispr.polish.PolishEngineLabels
import com.envi.wispr.recovery.*
import com.envi.wispr.ui.RecoverySpeechClient
import com.envi.wispr.ui.RescuedWords
import com.envi.wispr.ui.RescueOutcome
import com.envi.wispr.ui.HistorySaveObserver
import com.envi.wispr.ui.RecordingProtectionOwner
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Audio subordinate of HistoryRecoveryCoordinator. Only explicit visible-app/History events start a pass. */
internal class AudioHistoryRecovery(
    context: Context,
    private val repository: TranscriptRepository,
    private val writes: HistoryWriteQueue,
    private val words: RescuedWords,
    private val scope: CoroutineScope,
    private val recordings: RecordingProtectionOwner,
    private val transform: (String, FrozenRecoveryContext) -> String,
    private val gate: ModelSwapGate = ModelSwapGate.main,
) {
    private val application = context.applicationContext
    private val store = RecoveryAudioStore(application)
    private val main = Handler(Looper.getMainLooper())
    private var visible: Any? = null // Main-thread lifecycle identity; never an Activity.
    @Volatile private var current: Pass? = null
    private var transportDrained: CompletableDeferred<Unit>? = null
    private val cleanupRunning = AtomicBoolean()
    private val authenticated = ConcurrentHashMap<UUID, RecoveryRecordRef>()

    fun foregroundStarted(beforeWords: suspend () -> Boolean): AutoCloseable {
        checkMain()
        val lease = Any()
        visible = lease
        current?.close()
        request(beforeWords)
        return AutoCloseable { onMain {
            if (visible === lease) { visible = null; current?.close() }
        } }
    }

    fun historyOpened(beforeWords: suspend () -> Boolean) { checkMain(); request(beforeWords) }
    fun recordAdmitted() { checkMain(); current?.close() }
    fun authenticatedRefs(): Set<RecoveryRecordRef> = authenticated.values.toSet()
    fun deleted(takes: Set<UUID>) {
        current?.let { pass -> if (pass.take in takes) { pass.live.set(false); onMain { pass.close() } } }
    }

    private fun request(beforeWords: suspend () -> Boolean) {
        val lease = visible ?: return
        // Clear current only after the actual IO/transport wrapper exits, not when its Job is canceled.
        if (current != null || transportDrained?.isCompleted == false || !gate.idle) return
        val pass = Pass(lease)
        current = pass
        pass.job = scope.launch(Dispatchers.IO) {
            try {
                if (com.envi.wispr.device.DeviceTier.current == com.envi.wispr.device.DeviceTier.BLOCKED) return@launch
                if (!beforeWords()) return@launch
                pass.run()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional recovery has no generic History error or vendor prose. */ }
            finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    pass.client?.close()
                    if (current === pass) current = null
                }
            }
        }
    }

    private inner class Pass(private val visibility: Any) {
        val live = AtomicBoolean(true)
        lateinit var job: Job
        var client: RecoverySpeechClient? = null // Main thread only.
        var claimed = false // Main: accepted text may save quietly after foreground/preemption ends.
        @Volatile var take: UUID? = null
        fun wanted(): Boolean = live.get() && visible === visibility && current === this
        fun close() { live.set(false); client?.close(); if (!claimed) job.cancel() }

        suspend fun run() {
            recordings.retryClosedOnWorker()
            val snapshot = value(store.snapshotOnWorker()) ?: return
            val records = snapshot.ledger.slots.mapIndexedNotNull { slot, record ->
                record?.let { RecoveryRecordRef(snapshot.ledger.storeId, slot, it.identity) to it }
            }.sortedWith(compareBy<Pair<RecoveryRecordRef, RecoveryRecord>> { it.second.createdAtMs }
                .thenBy { it.first.identity.takeId.toString() })
            for ((ref, record) in records) {
                if (!withContext(Dispatchers.Main.immediate) { wanted() && gate.idle && transportDrained?.isCompleted != false }) return
                if (settled(ref, record)) continue
                when (record.phase) {
                    RecoveryRecordPhase.ARMABLE, RecoveryRecordPhase.WRITING, RecoveryRecordPhase.SEALED -> replay(ref)
                    RecoveryRecordPhase.RESERVED, RecoveryRecordPhase.ATTEMPT_RESERVED,
                    RecoveryRecordPhase.NATIVE_CONSUMED, RecoveryRecordPhase.TEXT_PENDING,
                    RecoveryRecordPhase.TERMINAL -> Unit
                }
            }
        }

        private suspend fun replay(ref: RecoveryRecordRef) {
            if (ref.identity.takeId in recordings.pendingTakeIds() || words.hasPendingOrStoredWords(ref.identity.takeId) || ModelStorage.usable(application, ModelManifest.parakeet) == null) return
            val lease = value(store.sealOrphanOnWorker(ref)) ?: return
            var disposition: RecoveryDisposition? = null
            take = ref.identity.takeId
            try {
                val frozen = lease.contextOnWorker()
                if (frozen.recipeId != ParakeetEngine.RECIPE_ID || frozen.formatProvenance != "android-pcm-s16le-16000-mono-v1") return
                authenticated[ref.identity.takeId] = ref
                currentCoroutineContext().ensureActive()
                if (!withContext(Dispatchers.Main.immediate) { wanted() && gate.idle }) return
                val authorization = value(lease.reserveAttemptOnWorker()) ?: return
                disposition = RecoveryDisposition.NO_REPLAY // Spent failure/preemption/empty closes quietly.
                val answer = CompletableDeferred<RecoveryResult?>()
                val localDrain = CompletableDeferred<Unit>()
                val started = withContext(Dispatchers.Main.immediate) {
                    if (!wanted() || transportDrained?.isCompleted == false) false else {
                        transportDrained = localDrain
                        val next = RecoverySpeechClient(application, authorization, scope,
                            result = { answer.complete(it) }, drained = { localDrain.complete(Unit) })
                        client = next
                        next.start { wanted() }
                    }
                }
                if (!started) return
                val result = withTimeoutOrNull(AsrBounds.LOAD_BOUND_MS + AsrBounds.requestBoundMs(RecordingLimits.MAX_DURATION_MS) + AsrBounds.DECODE_GRACE_MS) {
                    answer.await()
                } ?: return
                if (result.rawText.isBlank()) return
                val accepted = withContext(Dispatchers.Main.immediate) {
                    client?.close()
                    if (wanted()) { claimed = true; true } else false
                }
                if (!accepted) return
                disposition = null // Produced text/input stays retained while either write may still settle.
                val finalText = try { transform(result.rawText, frozen).ifBlank { result.rawText } }
                    catch (_: Exception) { result.rawText }
                val row = TranscriptEntity(originalText = result.rawText, finalText = finalText,
                    createdAtMs = frozen.createdAtMs, durationMs = result.durationMs,
                    speechEngine = result.model.displayName, polishEngine = PolishEngineLabels.OFF,
                    polishLatencyMs = 0L, insertionResult = InsertionResults.HISTORY_ONLY,
                    recovered = true, takeId = ref.identity.takeId.toString(),
                    recoveryStartSample = result.firstSample, recoveryPartial = result.partial,
                    recoveryModelIdentity = result.model.storedIdentity())
                val saved = writes.saveRecovered(row)
                val rescue = words.keepRecovered(RecoveredWordsRecord(row, result.sampleCount), saved)
                when (withTimeoutOrNull(HistorySaveObserver.HISTORY_SAVE_BOUND_MS) { saved.await() }) {
                    is RecoverySaveOutcome.Saved -> {
                        disposition = RecoveryDisposition.TEXT_SAVED
                        withContext(Dispatchers.Main.immediate) {
                            if (wanted()) Toast.makeText(application, "Recovered a recording\nSaved to History", Toast.LENGTH_LONG).show()
                        }
                    }
                    is RecoverySaveOutcome.PreservedExisting -> disposition = RecoveryDisposition.TEXT_SAVED
                    RecoverySaveOutcome.Deleted -> disposition = RecoveryDisposition.DISCARDED
                    is RecoverySaveOutcome.Failed, null -> {
                        if (withTimeoutOrNull(HistorySaveObserver.HISTORY_SAVE_BOUND_MS) { rescue.await() } == RescueOutcome.KEPT) {
                            disposition = RecoveryDisposition.TEXT_SAVED
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { client?.close(); client = null; claimed = false }
                withContext(NonCancellable + Dispatchers.IO) {
                    val terminal = disposition?.let { store.markTerminalOnWorker(ref, it) is RecoveryStoreResult.Ready } == true
                    val released = lease.closeOnWorker() == RecoveryClaimRelease.RELEASED
                    authenticated.remove(ref.identity.takeId, ref)
                    if (terminal && released && store.cleanupOnWorker(ref) is RecoveryStoreResult.Ready) recordings.forgetDisposed(ref)
                }
                take = null
            }
        }
    }

    /** Cleanup is event-driven and never creates an ASR binding or retries native speech. */
    fun requestCleanup() {
        if (!cleanupRunning.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recordings.retryClosedOnWorker()
                val snapshot = value(store.snapshotOnWorker()) ?: return@launch
                snapshot.ledger.slots.forEachIndexed { slot, record ->
                    record?.let { settled(RecoveryRecordRef(snapshot.ledger.storeId, slot, it.identity), it) }
                }
            } catch (_: Exception) { /* Unknown query/ownership defers; never infer empty/deleted. */ }
            finally { cleanupRunning.set(false) }
        }
    }

    private suspend fun settled(ref: RecoveryRecordRef, record: RecoveryRecord): Boolean {
        val disposition = when {
            repository.isTakeDeleted(ref.identity.takeId.toString()) -> RecoveryDisposition.DISCARDED
            repository.hasSavedWords(ref.identity.takeId.toString()) -> RecoveryDisposition.TEXT_SAVED
            record.phase == RecoveryRecordPhase.TERMINAL -> record.disposition
            else -> null
        } ?: return false
        if (store.markTerminalOnWorker(ref, disposition) is RecoveryStoreResult.Ready &&
            store.cleanupOnWorker(ref) is RecoveryStoreResult.Ready) recordings.forgetDisposed(ref)
        return true
    }

    private fun <T> value(result: RecoveryStoreResult<T>): T? = when (result) {
        is RecoveryStoreResult.Ready -> result.value
        RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> null // Defer, never claim an empty successful scan.
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() } }
}
