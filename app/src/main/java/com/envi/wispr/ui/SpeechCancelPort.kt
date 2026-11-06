package com.envi.wispr.ui

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One proxy's cancellation commands and actual return obligations. No worker starts until a cancel
 * needs sending. An idle worker retires; shutdown never abandons an issued call or cancels a Future.
 * Closing stops new registrations, while leases already held can reserve their mandatory late cancel.
 */
internal class SpeechCancelPort(
    private val failed: (Throwable) -> Unit,
    private val drained: () -> Unit = {},
    private val makeWorker: () -> ExecutorService = {
        Executors.newSingleThreadExecutor { Thread(it, "SpeechCancelCommand").apply { isDaemon = true } }
    },
) : AutoCloseable {
    private val lock = Any()
    private enum class State { OPEN, CLOSING, DRAINED }
    private var state = State.OPEN
    private var leases = 0
    private var worker: ExecutorService? = null

    inner class Lease internal constructor() : AutoCloseable {
        private var held = true
        fun reserve(): Lease = synchronized(lock) {
            check(held) { "Speech cancellation obligation lost its lease" }
            leases++
            Lease()
        }
        override fun close() {
            var notify = false
            val idle = synchronized(lock) {
                if (!held) return
                held = false
                check(leases > 0)
                leases--
                if (leases == 0 && state == State.CLOSING) { state = State.DRAINED; notify = true }
                if (leases == 0) worker.also { worker = null } else null
            }
            try { idle?.shutdown() } catch (failure: Throwable) { report(failure) }
            if (notify) runCatching(drained).onFailure(::report)
        }
    }

    fun lease(): Lease = synchronized(lock) {
        check(state == State.OPEN) { "Speech cancellation port is closing" }
        leases++
        Lease()
    }

    /** Lease reservation precedes queueing. Its release is the actual RPC finally, not caller cancellation. */
    fun send(reserved: Lease, command: () -> Unit) {
        try {
            val target = synchronized(lock) { worker ?: makeWorker().also { worker = it } }
            target.execute {
                try { command() } catch (failure: Throwable) { report(failure) }
                finally { reserved.close() }
            }
        } catch (failure: Throwable) {
            reserved.close()
            report(failure)
        }
    }

    internal fun report(failure: Throwable) { runCatching { failed(failure) } }
    val closedAndDrained: Boolean get() = synchronized(lock) { state == State.DRAINED }

    override fun close() {
        var notify = false
        val idle = synchronized(lock) {
            if (state == State.OPEN) state = State.CLOSING
            if (state == State.CLOSING && leases == 0) { state = State.DRAINED; notify = true }
            if (leases == 0) worker.also { worker = null } else null
        }
        try { idle?.shutdown() } catch (failure: Throwable) { report(failure) }
        if (notify) runCatching(drained).onFailure(::report)
    }
}
