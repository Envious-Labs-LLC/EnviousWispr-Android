package com.envi.wispr.asr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Harness Contract: real push-only events from :asr, never polling or inference from missing callbacks. */
internal class AsrProbeEvents(private val context: Context) : AutoCloseable {
    private val run = UUID.randomUUID().toString()
    private val monitor = Object()
    private val events = mutableListOf<JSONObject>()
    private var malformed: Throwable? = null
    private val listener = object : IAsrProbeListener.Stub() {
        override fun onEvent(eventJson: String?) {
            synchronized(monitor) {
                try {
                    checkNotNull(eventJson)
                    check(eventJson.length <= 16_384 && events.size < 256) { "Probe event bound exceeded" }
                    val event = JSONObject(eventJson)
                    check(number(event, "version") == 1L && event.get("run") == run) { "Wrong probe epoch/schema" }
                    listOf("sequence", "pid", "generation", "request", "callback", "nanos").forEach { number(event, it) }
                    check(event.get("kind") is String && event.get("take") is String)
                    events += event
                } catch (failure: Throwable) { malformed = failure }
                monitor.notifyAll()
            }
        }
    }

    fun register(boundMs: Long) = registration(listener, boundMs)

    private fun registration(target: IAsrProbeListener?, boundMs: Long) {
        check(boundMs > 0)
        val answered = CountDownLatch(1)
        var accepted = false
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { accepted = resultCode == Activity.RESULT_OK; answered.countDown() }
        }
        val intent = Intent().setComponent(ComponentName(context.packageName, "com.envi.wispr.asr.AsrCancellationProbeReceiver"))
            .putExtra("run", run).putExtra("take", "")
            .putExtras(Bundle().apply { putBinder("listener", target?.asBinder()) })
        context.sendOrderedBroadcast(intent, null, result, Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null)
        check(answered.await(boundMs, TimeUnit.MILLISECONDS) && accepted) { "The same-UID ASR observer did not register" }
    }

    fun await(kind: String, boundMs: Long, predicate: (JSONObject) -> Boolean = { true }): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs)
        synchronized(monitor) {
            while (true) {
                malformed?.let { throw AssertionError("Malformed ASR probe event", it) }
                events.firstOrNull { it.getString("kind") == kind && predicate(it) }?.let { return it }
                val left = deadline - System.nanoTime()
                check(left > 0) { "No ASR event $kind within its observation bound; observed ${events.map { it.getString("kind") }}" }
                TimeUnit.NANOSECONDS.timedWait(monitor, left)
            }
        }
    }

    fun snapshot(): JSONArray = synchronized(monitor) { JSONArray(events.map { JSONObject(it.toString()) }) }
    fun seen(kind: String, predicate: (JSONObject) -> Boolean): Boolean = synchronized(monitor) {
        events.any { it.getString("kind") == kind && predicate(it) }
    }
    fun close(boundMs: Long) { registration(null, boundMs) }
    override fun close() { close(5_000) }

    companion object {
        fun number(data: JSONObject, name: String): Long {
            val value = data.get(name)
            check(value is Int || value is Long) { "Probe $name was not an exact integer" }
            return (value as Number).toLong()
        }
    }
}
