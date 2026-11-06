package com.envi.wispr.asr

import java.util.concurrent.atomic.AtomicReference

/** A cancelled invocation is not a decode error or an empty successful transcript. */
internal class AsrCancelled : RuntimeException(null, null, false, false)

/** Required native cleanup was incomplete; callers must poison further native admission. */
internal class AsrCleanupFailure(cause: Throwable) : RuntimeException("Speech native cleanup failed", cause)

/**
 * One request's permanent cancellation and protected native-control lifetime. Logical cancellation
 * is a lock-free transition, so a UI/Binder caller never waits for JNI. The control executor terminates
 * live options under the short lifetime lock; the worker detaches under that SAME lock before closing.
 * Model execution never holds the lifetime lock. A cancelled request never becomes Open again.
 */
internal class AsrCancellation {
    private val lifetime = Any()
    private sealed interface State {
        data object Open : State
        data class Live(val terminate: () -> Unit) : State
        data class CancelPending(val scope: Live) : State
        data class Terminating(val scope: Live) : State
        data object Cancelled : State
    }
    private val state = AtomicReference<State>(State.Open)

    val isCancelled: Boolean get() = when (state.get()) {
        State.Open, is State.Live -> false
        is State.CancelPending, is State.Terminating, State.Cancelled -> true
    }
    fun check() { if (isCancelled) throw AsrCancelled() }

    /** Revokes new work immediately; it does not claim that native termination has run or retired. */
    fun requestCancellation() {
        while (true) {
            val current = state.get()
            val next = when (current) {
                State.Open -> State.Cancelled
                is State.Live -> State.CancelPending(current)
                is State.CancelPending, is State.Terminating, State.Cancelled -> return
            }
            if (state.compareAndSet(current, next)) return
        }
    }

    /** Run off the native worker and off UI/deadline threads. A detached old scope is never reached. */
    fun terminateNative() = synchronized(lifetime) {
        when (val current = state.get()) {
            is State.CancelPending -> {
                check(state.compareAndSet(current, State.Terminating(current.scope)))
                current.scope.terminate()
            }
            State.Open, is State.Live, is State.Terminating, State.Cancelled -> Unit
        }
    }

    /** Synchronous convenience for an owned control thread/component qualification, never a UI command. */
    fun cancel() { requestCancellation(); terminateNative() }

    /** The worker closes this lease BEFORE native options. Old leases cannot detach a replacement scope. */
    fun attach(terminate: () -> Unit): AutoCloseable {
        val attached = State.Live(terminate)
        synchronized(lifetime) {
            if (!state.compareAndSet(State.Open, attached)) {
                check()
                error("A speech request already has a native control scope")
            }
        }
        return AutoCloseable {
            synchronized(lifetime) {
                while (true) {
                    val current = state.get()
                    val next = when (current) {
                        State.Open, State.Cancelled -> break
                        is State.Live -> if (current === attached) State.Open else break
                        is State.CancelPending -> if (current.scope === attached) State.Cancelled else break
                        is State.Terminating -> if (current.scope === attached) State.Cancelled else break
                    }
                    if (state.compareAndSet(current, next)) break
                }
            }
        }
    }
}
