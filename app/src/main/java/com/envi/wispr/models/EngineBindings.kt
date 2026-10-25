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
    }

    private val held = ConcurrentHashMap<ServiceConnection, Held>()

    /**
     * Binds [connection] to [engine] unless a swap holds the gate. [onRevoked] runs on the main thread when a swap
     * unbinds it, so the owner can forget the binding (an explicit unbind never calls `onServiceDisconnected`).
     */
    fun bind(context: Context, engine: Engine, connection: ServiceConnection, flags: Int, onRevoked: () -> Unit = {}): Boolean =
        ModelSwapGate.main.ifOpen {
            lateinit var record: Held
            val wrapper = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    record.binder = binder
                    connection.onServiceConnected(name, binder)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    record.binder = null
                    connection.onServiceDisconnected(name)
                }
                override fun onBindingDied(name: ComponentName?) = connection.onBindingDied(name)
                override fun onNullBinding(name: ComponentName?) = connection.onNullBinding(name)
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
        val record = held.remove(connection) ?: return
        runCatching { record.context.unbindService(record.wrapper) }
    }

    /**
     * The swap, with the gate closed: unbinds every holder, posts each owner's [Held.onRevoked], and returns the binders
     * still alive, for the death acknowledgement the swap waits on.
     */
    fun revokeAll(post: (Runnable) -> Unit): List<IBinder> {
        val records = held.keys.toList().mapNotNull { held.remove(it) }
        records.forEach { record ->
            runCatching { record.context.unbindService(record.wrapper) }
            post(Runnable { record.onRevoked() })
        }
        return records.mapNotNull { record -> record.binder?.takeIf { it.isBinderAlive } }.distinct()
    }
}
