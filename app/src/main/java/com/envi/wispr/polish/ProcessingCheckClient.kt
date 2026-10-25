package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.envi.wispr.processing.IProcessingCheckCallback
import com.envi.wispr.processing.ProcessingBackend
import com.envi.wispr.processing.ProcessingCheckResult
import com.envi.wispr.processing.ProcessingCheckStatus
import com.envi.wispr.processing.ProcessingEnvironment
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A short-lived support-check binding. No editor, key, History or dictation text reaches it. */
internal class ProcessingCheckClient(context: Context) : Closeable {
    private val app = context.applicationContext
    private val lock = Any()
    private val calls = Executors.newSingleThreadExecutor { Thread(it, "ProcessingCheckCalls").apply { isDaemon = true } }
    private data class Pending(val backend: ProcessingBackend, val answer: (ProcessingCheckResult) -> Unit)
    private val pending = LinkedHashMap<Long, Pending>()
    private var binding: Binding? = null
    private var closed = false
    private val ids = AtomicLong(System.nanoTime())

    /** A fresh connection per binding prevents a late old callback from installing its binder in a new check. */
    private inner class Binding : ServiceConnection {
        var service: IPolishService? = null
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val ready = IPolishService.Stub.asInterface(binder)
            val queued = synchronized(lock) {
                if (closed || binding !== this) return
                service = ready
                pending.toList()
            }
            queued.forEach { (id, request) -> send(ready, id, request) }
        }
        override fun onServiceDisconnected(name: ComponentName?) = fail(this)
        override fun onNullBinding(name: ComponentName?) = fail(this)
        override fun onBindingDied(name: ComponentName?) = fail(this)
    }
    fun check(backend: ProcessingBackend, answer: (ProcessingCheckResult) -> Unit): Long {
        val id = ids.incrementAndGet()
        val request = Pending(backend, answer)
        var rejected = false
        var updating = false
        val ready = synchronized(lock) {
            check(!closed)
            pending[id] = request
            if (binding == null) {
                val created = Binding()
                binding = created
                // A model swap refuses the bind (#108): that check did not run, so it is cancelled, never failed.
                updating = !com.envi.wispr.models.ModelSwapGate.main.open
                if (!com.envi.wispr.models.EngineBindings.bind(app, com.envi.wispr.models.EngineBindings.Engine.POLISH, created, Context.BIND_AUTO_CREATE) { revoked(created) }) {
                    binding = null; pending.remove(id); rejected = true
                }
            }
            binding?.service
        }
        if (rejected) answer(if (updating) cancelled(id, request) else failed(id, request)) else if (ready != null) send(ready, id, request)
        return id
    }
    private fun failed(id: Long, request: Pending) = ProcessingCheckResult(
        id, request.backend, ProcessingEnvironment.s1ContextId(), ProcessingCheckStatus.RUNTIME_FAILED,
    )
    private fun cancelled(id: Long, request: Pending) = ProcessingCheckResult(
        id, request.backend, ProcessingEnvironment.s1ContextId(), ProcessingCheckStatus.CANCELLED,
    )
    /** A model swap revoked the binding (#108): every check still waiting is cancelled, not failed. */
    private fun revoked(source: Binding) {
        val lost = synchronized(lock) {
            if (binding !== source) return
            binding = null
            pending.toList().also { pending.clear() }
        }
        lost.forEach { (id, request) -> request.answer(cancelled(id, request)) }
    }
    private fun send(target: IPolishService, id: Long, request: Pending) {
        runCatching { calls.execute {
            if (synchronized(lock) { closed || pending[id] !== request }) return@execute
            runCatching {
                target.qualifyProcessing(id, request.backend.wire, object : IProcessingCheckCallback.Stub() {
                    override fun onChecked(result: ProcessingCheckResult?) {
                        finish(id, request, result?.takeIf { it.operationId == id && it.backend == request.backend } ?: failed(id, request))
                    }
                })
            }.onFailure { finish(id, request, failed(id, request)) }
        } }.onFailure { finish(id, request, failed(id, request)) }
    }
    private fun finish(id: Long, request: Pending, result: ProcessingCheckResult) {
        var released: Binding? = null
        val accepted = synchronized(lock) {
            val accepted = !closed && pending.remove(id, request)
            if (accepted && pending.isEmpty()) { released = binding; binding = null }
            accepted
        }
        released?.let { com.envi.wispr.models.EngineBindings.unbind(it) }
        if (accepted) request.answer(result)
    }
    private fun fail(source: Binding) {
        val lost = synchronized(lock) {
            if (binding !== source) return
            binding = null
            pending.toList().also { pending.clear() }
        }
        com.envi.wispr.models.EngineBindings.unbind(source)
        lost.forEach { (id, request) -> request.answer(failed(id, request)) }
    }
    fun cancelAll() {
        val state = synchronized(lock) {
            val snapshot = binding to pending.keys.toList()
            pending.clear(); binding = null
            snapshot
        }
        runCatching { calls.execute { state.second.forEach { id -> runCatching { state.first?.service?.cancelQualification(id) } } } }
        state.first?.let { com.envi.wispr.models.EngineBindings.unbind(it) }
    }
    override fun close() {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            val snapshot = binding to pending.keys.toList()
            pending.clear(); binding = null
            snapshot
        }
        calls.execute { state.second.forEach { id -> runCatching { state.first?.service?.cancelQualification(id) } } }
        state.first?.let { com.envi.wispr.models.EngineBindings.unbind(it) }
        calls.shutdown()
    }
}
