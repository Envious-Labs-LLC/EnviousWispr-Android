package com.envi.wispr.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.models.EngineBindings
import com.envi.wispr.models.ModelSwapGate
import com.envi.wispr.recovery.RecoveryReplayRef
import com.envi.wispr.recovery.RecoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One recovery transport episode. Cancellation detaches its consumer; a tail retains only small control identity. */
internal class RecoverySpeechClient(
    context: Context,
    private val replay: RecoveryReplayRef,
    private val scope: CoroutineScope,
    result: (RecoveryResult?) -> Unit,
    private val drained: () -> Unit,
    private val gate: ModelSwapGate = ModelSwapGate.main,
) : AutoCloseable {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val consumer = AtomicReference<((RecoveryResult?) -> Unit)?>(result)
    private val closed = AtomicBoolean()
    private val permit = AtomicBoolean()
    private val drainReported = AtomicBoolean()
    private var started = false // Main thread owns bindings and the proxy/handle fields.
    private var proxy: SpeechLink? = null
    private var submission: SpeechSubmission? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed.get()) { EngineBindings.unbind(this); return }
            if (binder == null) { answer(null); close(); return }
            try {
                val link = PipelineBindings.recoverySpeechProxy(IAsrService.Stub.asInterface(binder),
                    post = { task -> scope.launch(Dispatchers.IO) { task.run() }; Unit }, drained = ::reportDrained)
                proxy = link
                val prepared = link.prepareRecovery(replay, object : RecoverySpeechListener {
                    override fun onResult(result: RecoveryResult) = answer(result)
                    override fun onUnavailable() = answer(null)
                })
                submission = prepared
                // PreparedSpeechSubmission owns the late-submit/late-cancel race, just as for ordinary speech.
                scope.launch(Dispatchers.IO) {
                    try { prepared.submit() } catch (_: Exception) { answer(null) }
                }
            } catch (_: Exception) { answer(null); close() }
        }

        override fun onServiceDisconnected(name: ComponentName?) { answer(null); close() }
        override fun onBindingDied(name: ComponentName?) { answer(null); close() }
        override fun onNullBinding(name: ComponentName?) { answer(null); close() }
    }

    /** Main thread only. The caller already durably spent the attempt before creating this binding. */
    fun start(wanted: () -> Boolean): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed.get()) return false
        started = true
        when (gate.admitTake { gate.idle && !closed.get() && wanted() }) {
            ModelSwapGate.Admission.ADMITTED -> permit.set(true)
            ModelSwapGate.Admission.UPDATING, ModelSwapGate.Admission.NOT_IDLE -> { close(); return false }
        }
        val bound = EngineBindings.bind(application, EngineBindings.Engine.SPEECH, connection, Context.BIND_AUTO_CREATE,
            onRevoked = { answer(null); close() })
        if (!bound) close()
        return bound
    }

    private fun answer(result: RecoveryResult?) {
        consumer.getAndSet(null)?.invoke(result)
    }

    private fun reportDrained() {
        if (drainReported.compareAndSet(false, true)) drained()
    }

    /** Never waits for native retirement or a blocked RPC. EngineBindings retains late-unbind/death accounting. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        consumer.set(null)
        val cleanup = Runnable {
            try { runCatching { submission?.cancel() }; runCatching { proxy?.close() } }
            finally {
                EngineBindings.unbind(connection)
                if (permit.compareAndSet(true, false)) gate.releaseTake()
                if (proxy == null) reportDrained()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
    }
}
