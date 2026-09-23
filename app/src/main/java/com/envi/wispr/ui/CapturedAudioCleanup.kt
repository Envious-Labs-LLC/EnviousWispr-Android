package com.envi.wispr.ui

import java.util.concurrent.Executors

/**
 * The process's one captured-audio delete worker (#253). It outlives every Service instance, as the History
 * queue does (#115), so a take's file is deleted even when the Service is destroyed right after its ending.
 * It lives outside the session owner's files on purpose: the owner holds no executor of its own.
 */
internal object CapturedAudioCleanup {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "captured-audio-cleanup").apply { isDaemon = true }
    }

    fun execute(task: Runnable) = worker.execute(task)
}
