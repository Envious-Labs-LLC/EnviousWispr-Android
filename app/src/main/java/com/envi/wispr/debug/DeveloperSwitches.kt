package com.envi.wispr.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.DeveloperStored
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The owner of the hidden Developer switches (#378 D2), main process only.
 *
 * DataStore is the authority and stays main-only; every process reads the mirror, a flag file that exists
 * exactly when the switch is on. Every request (Detailed log on or off, Keep recordings on or off, the cold
 * start repair) runs on ONE dedicated worker in the order it was made, never on the UI thread, and only the
 * latest request for a switch may publish a settled state. A switch shows On or Off only when DataStore and
 * its flag agree; until then it is Pending, and a failure is Error, never a settled value.
 *
 * Detailed log Off is a barrier, not a poll: the worker takes all five processes' log locks in a fixed order
 * (a writer mid-batch holds its own, so the barrier waits for that batch), deletes the flag, verifies it is
 * gone, and only then reports Off. Every writer re-checks the flag under its lock before it writes, so no
 * line is written after the barrier returns.
 */
internal class DeveloperSwitches(
    private val store: Store,
    private val files: LogFiles,
    private val debuggable: Boolean,
    private val lockFor: (process: String) -> ProcessLogLock,
    dispatcher: CoroutineDispatcher,
    private val lockTimeoutMs: Long = LogWriter.LOCK_TIMEOUT_MS,
) {
    /** DataStore behind a seam so the JVM tests drive the ordering without an Android store. */
    interface Store {
        suspend fun read(): DeveloperStored
        suspend fun setUnlocked()
        suspend fun setDetailedLog(on: Boolean)
        suspend fun setKeepRecordings(on: Boolean)
    }

    sealed interface Switch {
        data object On : Switch
        data object Off : Switch
        data object Pending : Switch
        data class Error(val reason: String) : Switch
    }

    data class State(val unlocked: Boolean, val detailedLog: Switch, val keepRecordings: Switch)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val detailedSeq = AtomicLong(0)
    private val keepSeq = AtomicLong(0)
    private val mutableState = MutableStateFlow(State(unlocked = debuggable, Switch.Pending, Switch.Pending))

    val state: StateFlow<State> = mutableState

    /** Completes when the cold-start repair has run; the adb door waits on it before any call (D10). */
    val ready = CompletableDeferred<Unit>()

    fun coldStartRepair() {
        scope.launch {
            try {
                val stored = store.read()
                val detailed = if (stored.detailedLog ?: debuggable) turnOn(files.detailedLogFlag) else offBarrier()
                val keep = setFlag(files.keepRecordingsFlag, stored.keepRecordings ?: debuggable)
                mutableState.value = State(stored.unlocked || debuggable, detailed, keep)
            } finally {
                ready.complete(Unit)
            }
        }
    }

    fun unlock() {
        scope.launch {
            store.setUnlocked()
            mutableState.value = mutableState.value.copy(unlocked = true)
        }
    }

    fun requestDetailedLog(on: Boolean): Deferred<Switch> {
        val seq = detailedSeq.incrementAndGet()
        mutableState.value = mutableState.value.copy(detailedLog = Switch.Pending)
        return scope.async {
            val result = runCatching {
                store.setDetailedLog(on)
                if (on) turnOn(files.detailedLogFlag) else offBarrier()
            }.getOrElse { Switch.Error("Detailed log could not be saved: ${it.javaClass.simpleName}") }
            if (seq == detailedSeq.get()) mutableState.value = mutableState.value.copy(detailedLog = result)
            result
        }
    }

    fun requestKeepRecordings(on: Boolean): Deferred<Switch> {
        val seq = keepSeq.incrementAndGet()
        mutableState.value = mutableState.value.copy(keepRecordings = Switch.Pending)
        return scope.async {
            val result = runCatching {
                store.setKeepRecordings(on)
                setFlag(files.keepRecordingsFlag, on)
            }.getOrElse { Switch.Error("Keep recordings could not be saved: ${it.javaClass.simpleName}") }
            if (seq == keepSeq.get()) mutableState.value = mutableState.value.copy(keepRecordings = result)
            result
        }
    }

    private fun turnOn(flag: java.io.File): Switch {
        flag.parentFile?.mkdirs()
        if (!flag.exists()) flag.createNewFile()
        return if (flag.exists()) Switch.On else Switch.Error("The switch file could not be created")
    }

    private fun setFlag(flag: java.io.File, on: Boolean): Switch {
        if (on) return turnOn(flag)
        if (flag.exists()) flag.delete()
        return if (flag.exists()) Switch.Error("The switch file could not be removed") else Switch.Off
    }

    /** All five log locks in [LogFiles.PROCESSES] order, then the delete and its verification, inside them. */
    private fun offBarrier(): Switch {
        if (!files.detailedLogFlag.exists()) return Switch.Off
        fun hold(index: Int): Boolean {
            if (index == LogFiles.PROCESSES.size) {
                files.detailedLogFlag.delete()
                return !files.detailedLogFlag.exists()
            }
            val held = lockFor(LogFiles.PROCESSES[index]).withLock(lockTimeoutMs) { hold(index + 1) }
            return held?.value ?: false
        }
        return if (hold(0)) Switch.Off else Switch.Error("A part of the app did not let go of its log in time; still on")
    }

    companion object {
        @Volatile private var instance: DeveloperSwitches? = null

        fun of(context: Context): DeveloperSwitches = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): DeveloperSwitches {
            val prefs = AppPreferences(app)
            val files = LogFiles(app.filesDir, app.cacheDir)
            return DeveloperSwitches(
                store = object : Store {
                    override suspend fun read(): DeveloperStored = prefs.developerStored.first()
                    override suspend fun setUnlocked() = prefs.setDeveloperUnlocked()
                    override suspend fun setDetailedLog(on: Boolean) = prefs.setDetailedLog(on)
                    override suspend fun setKeepRecordings(on: Boolean) = prefs.setKeepRecordings(on)
                },
                files = files,
                debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                lockFor = { process -> ProcessLogLock.of(files.lock(process)) },
                dispatcher = Executors.newSingleThreadExecutor { Thread(it, "developer-switches").apply { isDaemon = true } }
                    .asCoroutineDispatcher(),
            )
        }
    }
}
