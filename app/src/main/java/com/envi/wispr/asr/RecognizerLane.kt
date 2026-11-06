package com.envi.wispr.asr

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * A borrowed, asynchronous SINGLE worker with ordered service generations. It owns scheduling only,
 * never a model. A later generation stays deferred until its predecessor proves actual free and
 * successful close-grace completion. Waiting generations never park the one native worker.
 * The process/fixture owns executor shutdown; this type cannot shut an executor down.
 */
internal class RecognizerLane(private val worker: Executor, private val watchdog: AsrWatchdog) {
    private val lock = Any()
    private val generations = ArrayDeque<Generation>()
    private var running: Generation? = null
    private var nextIdentity = 0L

    private enum class State { OPEN, CLOSING, RETIRED }

    inner class Generation internal constructor(val identity: Long) {
        private var state = State.OPEN
        private val commands = ArrayDeque<Runnable>()

        fun enqueue(command: () -> Unit): Boolean = update {
            if (state != State.OPEN || watchdog.wedged) false else {
                commands.addLast(Runnable(command))
                true
            }
        }

        /** Admission closes and the final native release is published in the SAME control transition. */
        fun seal(release: () -> Unit): Boolean = update {
            if (state != State.OPEN) false else {
                state = State.CLOSING
                commands.addLast(Runnable(release))
                true
            }
        }

        /** Called only after actual free, all old request tails, and the winning close-grace completion. */
        fun completeRetirement(): Boolean = update {
            check(state == State.CLOSING && commands.isEmpty()) { "Speech generation still owns commands" }
            if (watchdog.wedged) false else { state = State.RETIRED; true }
        }

        internal fun take(): Runnable? = commands.pollFirst()
        internal fun isRetired(): Boolean = state == State.RETIRED
    }

    fun generation(): Generation = update { Generation(++nextIdentity).also { generations.addLast(it) } }

    private fun <T> update(change: () -> T): T {
        var end: (() -> Unit)? = null
        val value = synchronized(lock) {
            val result = change()
            end = scheduleLocked()
            result
        }
        end?.invoke()
        return value
    }

    private fun scheduleLocked(): (() -> Unit)? {
        if (running != null || watchdog.wedged) return null
        while (generations.peekFirst()?.isRetired() == true) generations.removeFirst()
        val generation = generations.peekFirst() ?: return null
        val command = generation.take() ?: return null
        running = generation
        try {
            worker.execute {
                try { command.run() } finally {
                    update { check(running === generation); running = null }
                }
            }
        } catch (failure: Throwable) {
            running = null
            // Publish through the SAME ending authority before releasing the lane's control lock.
            return watchdog.prepareEnding("Speech worker refused command scheduling")
        }
        return null
    }
}
