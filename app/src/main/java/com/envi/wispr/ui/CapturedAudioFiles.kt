package com.envi.wispr.ui

import com.envi.wispr.audio.PcmAudio
import java.io.File
import java.util.concurrent.Executors

/**
 * The one owner of a take's captured-audio file on the main process's side (#358): its length and its delete. The
 * session owner only says which file and when; it never touches the file itself.
 *
 * Every delete is queued on [execute], never run on the caller's time. In production that is the process's one
 * delete worker, which outlives every Service instance as the History queue does (#115), so a take's file is
 * deleted even when the Service is destroyed right after its ending (#253).
 */
internal class CapturedAudioFiles(
    private val execute: (Runnable) -> Unit,
    private val warn: (String) -> Unit,
) {
    /** Queues the delete of [path] and returns; a blank path queues nothing. A failure says so, never throws. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        execute(Runnable {
            runCatching {
                val file = File(path)
                if (file.exists() && !file.delete()) warn("Unable to delete captured audio after terminal processing")
            }.onFailure { error -> warn("Unable to delete captured audio: ${error.javaClass.simpleName}") }
        })
    }

    /** The finished file's audio length in milliseconds, read now; 0 when there is no file or it cannot be read. */
    fun durationMs(path: String?): Long = runCatching {
        path?.let { (PcmAudio.durationSeconds(File(it).length()) * 1000f).toLong() }
    }.getOrNull()?.coerceAtLeast(0L) ?: 0L

    companion object {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "captured-audio-cleanup").apply { isDaemon = true }
        }

        /** The process's instance: the one delete worker, and the session's log tag the UAT collectors read (review round 1). */
        val PROCESS = CapturedAudioFiles(execute = worker::execute, warn = DebugSessionLog::warn)
    }
}
