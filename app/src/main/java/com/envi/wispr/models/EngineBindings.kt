package com.envi.wispr.models

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.envi.wispr.asr.AsrService
import com.envi.wispr.polish.PolishService
import java.util.concurrent.ConcurrentHashMap

/**
 * #108 plan §18.1, §19: every main-process binding to an engine service goes through here. A bind is refused while
 * [ModelSwapGate] is closed, and a swap revokes every binding at once, telling each owner, so no owner can keep an
 * engine process alive (or have Android re-create its service) across a model-folder write. Owners: `PipelineBindings`,
 * `EngineWarmUp`, `SpeechModelKeepAlive`, `ProcessingCheckClient`.
 */
internal object EngineBindings {
    enum class Engine(val service: Class<*>, val processSuffix: String) {
        SPEECH(AsrService::class.java, ":asr"),
        POLISH(PolishService::class.java, ":polish"),
    }

    private class Held(val context: Context, val wrapper: ServiceConnection, val onRevoked: () -> Unit) {
        @Volatile var binder: IBinder? = null
        /** The owner unbound before the connection arrived: Android may still create the service, so it is kept until then. */
        @Volatile var releaseWhenConnected = false
    }

    private val held = ConcurrentHashMap<ServiceConnection, Held>()

    /**
     * Every engine binder seen, kept after its owner unbinds: an engine may still be finishing work then
     * (`AsrService.onDestroy` queues its release behind it), and the swap waits for that binder's death too.
     */
    private val observed = ConcurrentHashMap.newKeySet<IBinder>()

    /**
     * Binds [connection] to [engine] unless a swap holds the gate. [onRevoked] runs on the main thread when a swap
     * unbinds it, so the owner can forget the binding (an explicit unbind never calls `onServiceDisconnected`).
     */
    fun bind(context: Context, engine: Engine, connection: ServiceConnection, flags: Int, onRevoked: () -> Unit = {}): Boolean =
        ModelSwapGate.main.ifOpen {
            // A connection still waiting on its earlier bind's arrival is not reused until that one is released.
            if (held.containsKey(connection)) return@ifOpen false
            lateinit var record: Held
            val wrapper = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    record.binder = binder
                    binder?.let { observed.add(it) }
                    if (record.releaseWhenConnected) {
                        unbind(connection)
                        return
                    }
                    connection.onServiceConnected(name, binder)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    record.binder = null
                    connection.onServiceDisconnected(name)
                }
                // Terminal: no connection follows, so the record goes before the owner hears, whichever came first.
                override fun onBindingDied(name: ComponentName?) {
                    val notify = !record.releaseWhenConnected
                    forget(connection, record)
                    if (notify) connection.onBindingDied(name)
                }
                override fun onNullBinding(name: ComponentName?) {
                    val notify = !record.releaseWhenConnected
                    forget(connection, record)
                    if (notify) connection.onNullBinding(name)
                }
            }
            record = Held(context, wrapper, onRevoked)
            held[connection] = record
            val bound = runCatching { context.bindService(Intent(context, engine.service), wrapper, flags) }.getOrDefault(false)
            if (!bound) {
                held.remove(connection)
                // Android asks for an unbind even when the bind was refused or threw.
                runCatching { context.unbindService(wrapper) }
            }
            bound
        } ?: false

    /** The owner's own unbind; a binding a swap already revoked is a no-op. */
    fun unbind(connection: ServiceConnection) {
        val record = held[connection] ?: return
        if (record.binder == null) {
            // Unbinding now would not stop Android creating the service; release it when the connection arrives.
            record.releaseWhenConnected = true
            return
        }
        forget(connection, record)
    }

    private fun forget(connection: ServiceConnection, record: Held) {
        if (held.remove(connection, record)) runCatching { record.context.unbindService(record.wrapper) }
    }

    /**
     * The swap, with the gate closed: unbinds every holder, posts each owner's [Held.onRevoked], and returns every engine
     * binder still alive, for the death acknowledgement the swap waits on. Null, revoking nothing, while a bind is still
     * connecting: its process cannot be identified yet, so the swap waits for the next idle moment.
     */
    fun revokeAll(post: (Runnable) -> Unit): List<IBinder>? {
        if (held.values.any { it.binder == null }) return null
        val records = held.keys.toList().mapNotNull { held.remove(it) }
        records.forEach { record ->
            runCatching { record.context.unbindService(record.wrapper) }
            post(Runnable { record.onRevoked() })
        }
        observed.removeIf { !it.isBinderAlive }
        return observed.toList()
    }
}
