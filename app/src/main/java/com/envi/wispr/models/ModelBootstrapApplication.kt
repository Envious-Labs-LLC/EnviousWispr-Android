package com.envi.wispr.models

import com.envi.wispr.telemetry.AppDefect
import android.app.Application
import android.content.Context
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.HistoryWriteQueue
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.ui.DebugSessionLog
import com.envi.wispr.ui.HistorySaveObserver
import com.envi.wispr.ui.RescuedWords
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Enqueues idempotent model bootstrap before any launcher or side-button activity can run. */
class ModelBootstrapApplication : Application() {
    companion object {
        /**
         * The process's one History write queue (#115): the session owner and the paste service both write a
         * take's row, and the order those writes land in is the order they were enqueued here, on one worker
         * that outlives every Service. Built on first use, in the default process, where both writers live.
         */
        internal fun historyWrites(context: Context): HistoryWriteQueue =
            (context.applicationContext as ModelBootstrapApplication).historyWrites

        /**
         * The process's one History save observer (#304), beside the write queue and as long-lived: a save's
         * diagnostics must outlive the Service, which stops right after an ordinary take hands its words over.
         */
        internal fun historySaves(context: Context): HistorySaveObserver =
            (context.applicationContext as ModelBootstrapApplication).historySaves

        /**
         * The process's one rescue store (#288), beside the save observer and as long-lived: a take's words are kept on
         * the phone until History saves them, which can be after the Service has stopped.
         */
        internal fun rescuedWords(context: Context): RescuedWords =
            (context.applicationContext as ModelBootstrapApplication).rescuedWords

        /** The process's one start-up History recovery (#346), shared by the session owner and the History screen. */
        internal fun historyRecovery(context: Context): com.envi.wispr.history.HistoryRecoveryCoordinator =
            (context.applicationContext as ModelBootstrapApplication).historyRecovery
    }

    private val historyRecovery: com.envi.wispr.history.HistoryRecoveryCoordinator by lazy {
        com.envi.wispr.history.HistoryRecoveryCoordinator(
            repository = TranscriptRepository(EnviousWisprDatabase.get(this).transcriptDao()),
            rescuedWords = rescuedWords,
            // Never cancelled: a Service stopping must not cancel a run the History screen waits on.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = System::currentTimeMillis,
            log = { DebugSessionLog.log(it) },
            warn = { DebugSessionLog.warn(it) },
        )
    }

    private val rescuedWords: RescuedWords by lazy {
        RescuedWords(
            dir = java.io.File(filesDir, "rescued-words"),
            // Never cancelled: it lives as long as the process, like the queue.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            wallClock = System::currentTimeMillis,
            warn = { DebugSessionLog.warn(it) },
        )
    }

    private val historySaves: HistorySaveObserver by lazy {
        HistorySaveObserver(
            // Never cancelled: it lives as long as the process, like the queue.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = SystemClock::elapsedRealtime,
            // The session's own log tag, where these lines were before #304.
            warn = { DebugSessionLog.warn(it) },
            defectSink = Telemetry::defect,
            breadcrumb = Telemetry::breadcrumb,
        )
    }

    private val historyWrites: HistoryWriteQueue by lazy {
        HistoryWriteQueue(
            TranscriptRepository(EnviousWisprDatabase.get(this).transcriptDao()),
            onOverload = { Telemetry.defect(AppDefect.HistoryQueueOverloaded) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // ABOVE the process gate on purpose (#176): every process, including `:audio`, `:asr`, `:vad`
        // and `:polish`, boots its own crash reporting here; only main goes on to PostHog. A limb: it
        // never throws and never blocks on the network.
        Telemetry.bootstrap(this)
        if (Application.getProcessName() != packageName) return
        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.parakeet)
        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.s1)
    }
}
