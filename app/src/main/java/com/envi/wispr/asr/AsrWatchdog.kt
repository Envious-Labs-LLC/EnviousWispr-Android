package com.envi.wispr.asr

import com.envi.wispr.process.EngineDeadline
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `:asr`'s hard bound on its own native work (#357). A native load or decode ignores `Thread.interrupt`, and the
 * release queues behind it (`RecognizerOwner`), so a wedged one would hold the process's only worker for good and
 * every later take would queue behind it. Expiry therefore ends the process ([endProcess]); the owner sees the
 * disconnect, and the next take binds a fresh `:asr`.
 */
internal class AsrWatchdog(
    private val deadline: EngineDeadline,
    private val endProcess: (String) -> Unit,
) {
    private val expired = AtomicBoolean(false)

    /** True once any bound expired: the process is ending and must not report ready. */
    val wedged: Boolean get() = expired.get()

    /**
     * Runs [work] under [boundMs]. Returns its value (or its throw) when it finished first; null when the bound expired
     * first, in which case the process is already ending and the late value or throw must deliver nothing.
     */
    fun <T> guard(boundMs: Long, what: String, work: () -> T): T? {
        val handle = deadline.arm(boundMs) {
            expired.set(true)
            endProcess("$what outlived its $boundMs ms bound")
        }
        val value = try {
            work()
        } catch (error: Throwable) {
            // A throw after the bound is late too (review round 1): the process is ending, so it reports nothing.
            if (!handle.cancel()) return null
            throw error
        }
        return if (handle.cancel()) value else null
    }
}
