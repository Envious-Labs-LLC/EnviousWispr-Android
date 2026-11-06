package com.envi.wispr.ui

/**
 * Shared by the real Binder proxy and its JVM peer. Delivery completion and actual submission return
 * are separate domains: an early callback cannot erase the post-return cancellation obligation.
 */
internal class PreparedSpeechSubmission(
    listener: SpeechListener,
    private val issue: (SpeechListener) -> Unit,
    private val cancelRemote: () -> Unit,
    private val port: SpeechCancelPort,
    private val registrationLease: SpeechCancelPort.Lease,
    private val tailLease: SpeechCancelPort.Lease,
    private val settled: (PreparedSpeechSubmission) -> Unit,
) : SpeechSubmission, SpeechListener {
    private sealed interface Delivery {
        data class Awaiting(val listener: SpeechListener) : Delivery
        data object CallbackObserved : Delivery
        data object ThrowObserved : Delivery
        data object Suppressed : Delivery
    }
    private enum class Wire { PREPARED, IN_CALL, CANCEL_PENDING_RETURN, RETURNED, SKIPPED, CANCEL_RESERVED }
    private enum class LeaseState { HELD, RELEASED }
    private val lock = Any()
    private var delivery: Delivery = Delivery.Awaiting(listener)
    private var wire = Wire.PREPARED
    private var registration = LeaseState.HELD
    private var tail = LeaseState.HELD

    override fun submit() {
        val send = synchronized(lock) {
            if (wire != Wire.PREPARED) false else { wire = Wire.IN_CALL; true }
        }
        if (!send) return
        try { issue(this) } catch (failure: Throwable) {
            returned(threw = true)
            throw failure // Preserve the exact original DeadObjectException/ordinary exception policy.
        }
        returned(threw = false)
    }

    private fun returned(threw: Boolean) {
        val actions = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val cancel = threw || wire == Wire.CANCEL_PENDING_RETURN
            check(wire == Wire.IN_CALL || wire == Wire.CANCEL_PENDING_RETURN)
            if (threw && delivery is Delivery.Awaiting) delivery = Delivery.ThrowObserved
            wire = if (cancel) Wire.CANCEL_RESERVED else Wire.RETURNED
            if (cancel) {
                reserveCancel(actions)
                releaseTail(actions)
            }
            settle(actions)
        }
        runActions(actions)
    }

    override fun cancel() {
        val actions = mutableListOf<() -> Unit>()
        synchronized(lock) {
            // A v1 caller exports no answered tail. A close snapshot may outlive its settled callback.
            if (wire == Wire.RETURNED && registration == LeaseState.RELEASED && tail == LeaseState.RELEASED) return
            if (delivery is Delivery.Awaiting) delivery = Delivery.Suppressed
            when (wire) {
                Wire.PREPARED -> wire = Wire.SKIPPED
                Wire.IN_CALL -> wire = Wire.CANCEL_PENDING_RETURN
                Wire.RETURNED -> { wire = Wire.CANCEL_RESERVED; reserveCancel(actions) }
                Wire.CANCEL_PENDING_RETURN, Wire.SKIPPED, Wire.CANCEL_RESERVED -> Unit
            }
            releaseTail(actions)
            settle(actions)
        }
        runActions(actions)
    }

    private fun reserveCancel(actions: MutableList<() -> Unit>) {
        val source = if (registration == LeaseState.HELD) registrationLease else tailLease
        val reserved = source.reserve() // Reserve BEFORE another thread can close the port or release this lease.
        actions += { port.send(reserved, cancelRemote) }
    }

    private fun releaseTail(actions: MutableList<() -> Unit>) {
        if (tail == LeaseState.HELD) { tail = LeaseState.RELEASED; actions += { tailLease.close() } }
    }

    /** The retained v1 method exports no handle; return debt/awaited callback still retains registration. */
    fun releaseUnexposedHandle() {
        val actions = mutableListOf<() -> Unit>()
        synchronized(lock) { releaseTail(actions) }
        runActions(actions)
    }

    private fun settle(actions: MutableList<() -> Unit>) {
        val done = when (wire) {
            Wire.PREPARED, Wire.IN_CALL, Wire.CANCEL_PENDING_RETURN -> false
            Wire.RETURNED -> delivery !is Delivery.Awaiting
            Wire.SKIPPED, Wire.CANCEL_RESERVED -> true
        }
        if (done && registration == LeaseState.HELD) {
            registration = LeaseState.RELEASED
            actions += { registrationLease.close() }
            actions += { settled(this) }
        }
    }

    private fun answer(answer: (SpeechListener) -> Unit) {
        val actions = mutableListOf<() -> Unit>()
        val listener = synchronized(lock) {
            when (wire) {
                Wire.PREPARED, Wire.SKIPPED -> null
                Wire.IN_CALL, Wire.CANCEL_PENDING_RETURN, Wire.RETURNED, Wire.CANCEL_RESERVED -> {
                    val awaiting = delivery as? Delivery.Awaiting
                    if (awaiting == null) null else {
                        delivery = Delivery.CallbackObserved
                        settle(actions)
                        awaiting.listener
                    }
                }
            }
        }
        runActions(actions)
        if (listener != null) answer(listener)
    }

    override fun onResult(text: String?) = answer { it.onResult(text) }
    override fun onError(message: String?) = answer { it.onError(message) }
    override fun onFailure(reason: Int, detail: String?) = answer { it.onFailure(reason, detail) }

    private fun runActions(actions: List<() -> Unit>) {
        actions.forEach { action -> try { action() } catch (failure: Throwable) { port.report(failure) } }
    }
}
