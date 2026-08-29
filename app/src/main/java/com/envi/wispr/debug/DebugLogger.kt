package com.envi.wispr.debug

import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured debug logging for EnviousWispr.
 *
 * Features:
 * - All logs go to logcat via standard Android tags
 * - Optional file logging to /sdcard/EnviousWispr/debug.log
 * - Pipeline profiling: mark events, get timing summary
 * - Thread-safe: DateTimeFormatter (immutable), AtomicInteger, ConcurrentLinkedQueue
 *
 * File logging must be explicitly enabled — off by default to avoid
 * unnecessary I/O during normal use.
 */
object DebugLogger {

    private const val LOG_PATH = "/sdcard/EnviousWispr/debug.log"
    private const val MAX_LOG_SIZE_BYTES = 1_000_000L
    private const val MAX_MARKERS = 500
    private const val META_TAG = "DebugLogger"

    // DateTimeFormatter is thread-safe (immutable), unlike SimpleDateFormat
    private val dateFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var fileLoggingEnabled = false

    @Volatile
    private var pipelineStartTime = 0L

    private val markers = ConcurrentLinkedQueue<Pair<String, Long>>()
    private val markerCount = AtomicInteger(0)

    // Persistent writer — opened once per session, avoids open/close per log line
    private var logWriter: BufferedWriter? = null
    private val writerLock = Any()

    /**
     * Enable writing logs to /sdcard/EnviousWispr/debug.log.
     * Call once at app startup if file-based debugging is needed.
     */
    fun enableFileLogging() {
        if (!Environment.isExternalStorageManager()) {
            Log.w(META_TAG, "MANAGE_EXTERNAL_STORAGE not granted — file logging disabled")
            return
        }

        val file = File(LOG_PATH)
        file.parentFile?.mkdirs()

        // Truncate and open writer inside the lock to avoid race condition
        // between truncation and writer opening.
        synchronized(writerLock) {
            try {
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    truncateLog(file)
                }
                logWriter = BufferedWriter(FileWriter(file, true))
                fileLoggingEnabled = true
                logWriter?.write("=== Session ${now()} ===\n")
                logWriter?.flush()
            } catch (e: Exception) {
                Log.w(META_TAG, "Failed to open log file", e)
            }
        }
    }

    /**
     * Close the file writer. Call on app exit for clean shutdown.
     */
    fun close() {
        synchronized(writerLock) {
            try {
                logWriter?.flush()
                logWriter?.close()
            } catch (_: Exception) {}
            logWriter = null
            fileLoggingEnabled = false
        }
    }

    /**
     * Start a new pipeline timing session. Clears all previous markers.
     */
    fun startPipeline() {
        pipelineStartTime = SystemClock.elapsedRealtime()
        markers.clear()
        markerCount.set(0)
        log("Pipeline", "START")
    }

    /**
     * Record a named timing marker relative to pipeline start.
     * Capped at [MAX_MARKERS] to prevent unbounded growth.
     */
    fun mark(tag: String, event: String) {
        val elapsed = SystemClock.elapsedRealtime() - pipelineStartTime
        if (markerCount.getAndIncrement() < MAX_MARKERS) {
            markers.add(event to elapsed)
        }
        log(tag, "$event [+${elapsed}ms]")
    }

    /**
     * Get a one-line summary of all pipeline markers and total time.
     */
    fun pipelineSummary(): String {
        val total = SystemClock.elapsedRealtime() - pipelineStartTime
        val sb = StringBuilder("Pipeline: ${total}ms total")
        for ((event, time) in markers) {
            sb.append(" | $event:${time}ms")
        }
        return sb.toString()
    }

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        if (fileLoggingEnabled) {
            writeToFile("${now()} [$tag] $message")
        }
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        if (fileLoggingEnabled) {
            writeToFile("${now()} [WARN:$tag] $message")
        }
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (fileLoggingEnabled) {
            writeToFile("${now()} [ERROR:$tag] $message")
            throwable?.stackTraceToString()?.take(500)?.let { trace ->
                if (trace.isNotEmpty()) writeToFile(trace)
            }
        }
    }

    private fun now(): String = LocalTime.now().format(dateFormat)

    private fun writeToFile(line: String) {
        synchronized(writerLock) {
            try {
                logWriter?.write("$line\n")
                logWriter?.flush()
            } catch (_: Exception) {
                // Never let file logging failures affect the app
            }
        }
    }

    private fun truncateLog(file: File) {
        try {
            val raf = java.io.RandomAccessFile(file, "r")
            val midpoint = raf.length() / 2
            raf.seek(midpoint)
            raf.readLine()
            val tail = ByteArray((raf.length() - raf.filePointer).toInt())
            raf.readFully(tail)
            raf.close()
            file.writeBytes(tail)
            Log.i(META_TAG, "Truncated log from ${midpoint * 2} to ${tail.size} bytes")
        } catch (e: Exception) {
            Log.w(META_TAG, "Log truncation failed", e)
        }
    }
}