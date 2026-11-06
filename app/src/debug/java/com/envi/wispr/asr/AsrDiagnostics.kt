package com.envi.wispr.asr

import android.os.Process
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Push-only qualification observations. No model state/authority, timers, polling, transcript or completed-job map. */
internal object AsrDiagnostics {
    private data class Subscription(val run: String, val take: String, val listener: IAsrProbeListener)
    private val lock = Any()
    private var subscription: Subscription? = null
    private var sequence = 0L

    fun subscribe(run: String, take: String, listener: IAsrProbeListener?) = synchronized(lock) {
        require(canonical(run) && (take.isEmpty() || canonical(take)))
        if (listener == null && subscription?.run != run) return@synchronized
        subscription = listener?.let { Subscription(run, take, it) }
    }
    private fun canonical(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun emit(kind: String, generation: Long, request: Long = 0, take: String = "", callback: Int = 0, extra: (JSONObject) -> Unit = {}) {
        val event = synchronized(lock) {
            val target = subscription ?: return
            if (take.isNotEmpty() && (!canonical(take) || (target.take.isNotEmpty() && take != target.take))) return
            val data = JSONObject().put("version", 1).put("run", target.run).put("sequence", ++sequence)
                .put("pid", Process.myPid()).put("kind", kind).put("generation", generation)
                .put("request", request).put("take", take).put("callback", callback).put("nanos", System.nanoTime())
            extra(data)
            target to data.toString()
        }
        // Small one-way events; no synchronous reply or model/control call under the observation lock.
        runCatching { event.first.listener.onEvent(event.second) }.onFailure {
            synchronized(lock) { if (subscription === event.first) subscription = null }
        }
    }

    fun registered(generation: Long, request: Long, take: String, callback: Int) = emit("registered", generation, request, take, callback)
    fun active(generation: Long, request: Long, take: String, callback: Int) = emit("active", generation, request, take, callback)
    fun callbackDied(generation: Long, request: Long, take: String, callback: Int) = emit("callback_died", generation, request, take, callback)
    fun retired(generation: Long, request: Long, take: String, callback: Int, outcome: AsrRequest.Retirement, cancelled: Boolean) =
        emit("retired", generation, request, take, callback) { it.put("outcome", outcome.name).put("cancelled", cancelled) }
    fun modelOpening(generation: Long) = emit("model_opening", generation)
    fun ownerRetired(generation: Long) = emit("owner_retired", generation)

    fun observer(generation: Long, request: Long, take: String, callback: Int): AsrObserver {
        if (synchronized(lock) { subscription == null }) return AsrObserver.NONE
        return object : AsrObserver {
            private val firstEncoder = AtomicBoolean(true)
            override fun entered(call: AsrObserver.Call) {
                if (call.stage == AsrObserver.Stage.ENCODER && firstEncoder.compareAndSet(true, false)) {
                    emit("encoder", generation, request, take, callback) { data -> describe(data, call) }
                }
            }
            override fun failed(call: AsrObserver.Call, failure: ai.onnxruntime.OrtException) {
                emit("native_failure", generation, request, take, callback) { data ->
                    describe(data, call)
                    data.put("raw_code", failure.code.name).put("raw_message", failure.message)
                }
            }
            private fun describe(data: JSONObject, call: AsrObserver.Call) {
                data.put("model", call.model).put("invocation", call.invocation).put("serial", call.serial)
                    .put("stage", call.stage.name).put("session", call.session).put("options", call.options).put("thread", call.thread)
            }
        }
    }
}
