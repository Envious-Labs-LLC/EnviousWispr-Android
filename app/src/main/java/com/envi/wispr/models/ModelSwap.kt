package com.envi.wispr.models

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #108 plan §17.4, §18.1, §19.1: every write to a model's final folder (an update's admission, a first install,
 * adoption, Remove, Repair) runs here, in the main process, with dictation paused.
 *
 * 1. The gate closes only when no take holds a permit; new takes, warm-ups, qualifications and binds are refused.
 * 2. Every engine binding is revoked (`EngineBindings`), so Android cannot re-create an engine service.
 * 3. A death recipient is linked to each engine binder still alive, then `:asr` and `:polish` are stopped. Every
 *    linked binder must report its death and neither process may be listed before anything on disk changes. A
 *    process with no binding has no service instance (the engines are bound-only), so nothing in it can open a
 *    model file; for it, absence from the process list is the evidence.
 * 4. On a timeout nothing is written, the gate reopens, and the caller tries again at the next idle moment.
 *
 * The engines then cold-load the new files on the next take (`usableDescriptor`).
 */
internal object ModelSwap {
    private const val TAG = "ModelSwap"
    private const val STOP_DEADLINE_MS = 5_000L

    /** Runs [mutation] with dictation paused and both engines stopped; null when it could not run now. */
    fun <T> exclusive(context: Context, mutation: () -> T): T? {
        val gate = ModelSwapGate.main
        if (!gate.beginSwap()) return null
        try {
            val main = Handler(Looper.getMainLooper())
            val binders = EngineBindings.revokeAll(post = { main.post(it) })
            if (!stopEngines(context, binders)) {
                DebugLogger.warn(TAG, "Engines did not stop in time; the model change waits")
                return null
            }
            return mutation()
        } finally {
            gate.endSwap()
        }
    }

    private fun stopEngines(context: Context, binders: List<IBinder>): Boolean {
        val deaths = CountDownLatch(binders.size)
        binders.forEach { binder ->
            val linked = runCatching { binder.linkToDeath({ deaths.countDown() }, 0) }.isSuccess
            // Already dead: linkToDeath throws, and that IS the acknowledgement.
            if (!linked) deaths.countDown()
        }
        val names = EngineBindings.Engine.entries.map { context.packageName + it.processSuffix }.toSet()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        fun running() = runCatching { manager.runningAppProcesses.orEmpty().filter { it.processName in names } }.getOrNull()
        running()?.forEach { android.os.Process.killProcess(it.pid) } ?: return false
        val deadline = SystemClock.elapsedRealtime() + STOP_DEADLINE_MS
        if (!deaths.await(STOP_DEADLINE_MS, TimeUnit.MILLISECONDS)) return false
        while (true) {
            val left = running() ?: return false
            if (left.isEmpty()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            // A process that started again after the first kill (a bind already in flight) is stopped too.
            left.forEach { android.os.Process.killProcess(it.pid) }
            Thread.sleep(50)
        }
    }
}
