package com.envi.wispr.polish

import com.envi.wispr.process.EngineDeadline
import com.envi.wispr.processing.ProcessingCheckRegistry
import com.envi.wispr.processing.ProcessingCheckStatus
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded canary on the existing engine worker, including its release and cancelled exits. */
internal class ProcessingQualificationTask(
    private val deadline: EngineDeadline,
    private val budgetMs: Long,
    private val poisoned: AtomicBoolean,
    private val destroyed: () -> Boolean,
    private val close: () -> Unit,
    private val invalidate: () -> Unit,
    private val released: () -> Unit,
    private val endProcess: (String) -> Unit,
) {
    fun run(entry: ProcessingCheckRegistry.Entry, load: () -> ProcessingCheckStatus, canary: () -> ProcessingCheckStatus, answer: (ProcessingCheckStatus) -> Unit) {
        var counted = true
        fun release() { if (counted) { counted = false; released() } }
        fun deliver(status: ProcessingCheckStatus) { entry.deliverOnce { answer(status) } }
        if (entry.isCancelled || destroyed() || poisoned.get()) { release(); return }
        val guard = try { deadline.arm(budgetMs) {
            poisoned.set(true); deliver(ProcessingCheckStatus.EXPIRED); endProcess("processing qualification expired")
        } } catch (error: Exception) {
            poisoned.set(true); release(); deliver(ProcessingCheckStatus.RUNTIME_FAILED); endProcess("qualification deadline unavailable"); return
        }
        var result: ProcessingCheckStatus? = null
        try {
            invalidate()
            result = load()
            if (entry.isCancelled || destroyed() || poisoned.get()) return
            if (result == ProcessingCheckStatus.AVAILABLE) result = canary()
            if (result == ProcessingCheckStatus.EXPIRED) {
                poisoned.set(true); guard.cancel(); release(); deliver(ProcessingCheckStatus.EXPIRED)
                endProcess("processing canary expired")
            }
        } catch (error: Exception) {
            if (error is S1RuntimeReleaseException) {
                poisoned.set(true); guard.cancel(); release(); deliver(ProcessingCheckStatus.RUNTIME_FAILED)
                endProcess("processing replacement release failed")
            }
            result = if (error is S1RuntimeInitializationException) ProcessingCheckStatus.RUNTIME_FAILED else ProcessingCheckStatus.LOAD_FAILED
        } finally {
            // Native release is inside the SAME bound as construction and inference.
            if (!poisoned.get()) try { close() } catch (error: Exception) {
                poisoned.set(true); guard.cancel(); release(); deliver(ProcessingCheckStatus.LOAD_FAILED)
                endProcess("processing qualification close failed")
            }
            invalidate()
            val completed = guard.cancel()
            release()
            if (completed && !destroyed() && !poisoned.get()) result?.let(::deliver)
        }
    }
}
