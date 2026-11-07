package com.envi.wispr.audio

import android.content.Context
import com.envi.wispr.recovery.RecoveryAudioStore
import com.envi.wispr.recovery.RecoveryAudioWriter
import com.envi.wispr.recovery.RecoveryContextDigest
import com.envi.wispr.recovery.RecoveryRecordIdentity
import com.envi.wispr.recovery.RecoveryRecordRef
import com.envi.wispr.recovery.RecoveryStoreResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One optional attachment preparation at a time; a blocked provider never queues recorder commands. */
internal class RecoveryCaptureAttachment(
    context: Context,
    private val install: (RecoveryRecordRef, RecoveryAudioWriter) -> Boolean,
) {
    private val application = context.applicationContext
    private val preparing = AtomicBoolean()

    fun request(takeId: String?, storeId: String?, slot: Int, generation: Long, contextDigest: ByteArray?) {
        val ref = runCatching {
            val take = UUID.fromString(takeId)
            val store = UUID.fromString(storeId)
            require(take.toString() == takeId && store.toString() == storeId && slot in 0..1 && generation > 0L)
            RecoveryRecordRef(store, slot, RecoveryRecordIdentity(take, generation, RecoveryContextDigest.of(requireNotNull(contextDigest))))
        }.getOrNull() ?: return
        if (!preparing.compareAndSet(false, true)) return
        val started = runCatching {
            Thread({
                var lease: RecoveryAudioStore.AudioWriterLease? = null
                var writer: RecoveryAudioWriter? = null
                try {
                    when (val answer = RecoveryAudioStore(application).openAudioWriterOnWorker(ref)) {
                        is RecoveryStoreResult.Ready -> lease = answer.value
                        RecoveryStoreResult.Busy, is RecoveryStoreResult.Unavailable -> return@Thread
                    }
                    writer = RecoveryAudioWriter.startOnWorker(checkNotNull(lease))
                    lease = null // The writer factory owns closure even when it cannot publish a writer.
                    val ready = writer
                    if (ready != null && install(ref, ready)) writer = null
                } catch (_: Throwable) {
                    // Optional protection only. No content, vendor exception or file path enters diagnostics.
                } finally {
                    runCatching { writer?.finishOnCaptureClosed(0L, false) }
                    runCatching { lease?.closeOnWorker() }
                    preparing.set(false)
                }
            }, "ProtectedRecordingAttachment").apply { isDaemon = true }.start()
        }.isSuccess
        if (!started) preparing.set(false)
    }
}
