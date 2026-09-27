package com.envi.wispr.debug

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * One process's log lock (#378 D4): an `fcntl` file lock on `logs/<process>.lock`, honoured across the app's
 * five processes, taken only after an in-process mutex. The mutex is not decoration: a second overlapping
 * `FileChannel` lock in the SAME process throws [OverlappingFileLockException] instead of waiting, and Linux
 * drops a process's record lock when ANY descriptor it holds on the file closes, so every holder in one
 * process must go through one channel and one mutex.
 *
 * Holders: the process's own [LogWriter] (every append batch, rotation and snapshot pin), and in main only,
 * the Detailed log Off barrier, which takes all five. Nothing else.
 */
internal class ProcessLogLock private constructor(private val file: File) {
    private val mutex = ReentrantLock()

    /** Opened once and never closed: closing any descriptor on the file would release the process's lock. */
    private val channel by lazy {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").channel
    }

    /**
     * Runs [block] holding the lock, waiting up to [timeoutMs] for it (the other process's holder, or this
     * process's own). Returns null, having run nothing, when the wait ran out.
     */
    fun <T> withLock(timeoutMs: Long, block: () -> T): Held<T>? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (!mutex.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return null
        try {
            var lock: FileLock? = null
            while (lock == null) {
                lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    if (System.nanoTime() >= deadline) return null
                    Thread.sleep(RETRY_MS)
                }
            }
            try {
                return Held(block())
            } finally {
                runCatching { lock.release() }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** A value produced under the lock; wrapped so a null result is distinguishable from a timeout. */
    class Held<T>(val value: T)

    companion object {
        /** Short, and only while contended: a holder keeps the lock for one batch. Never an idle poll. */
        private const val RETRY_MS = 5L

        private val byPath = ConcurrentHashMap<String, ProcessLogLock>()

        /** The one instance for [file] in this process, so every holder shares one channel and one mutex. */
        fun of(file: File): ProcessLogLock = byPath.getOrPut(file.absolutePath) { ProcessLogLock(file) }
    }
}
