package com.envi.wispr.models

import android.app.Application
import android.content.Context
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.HistoryWriteQueue
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.telemetry.Telemetry

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
    }

    private val historyWrites: HistoryWriteQueue by lazy {
        HistoryWriteQueue(TranscriptRepository(EnviousWisprDatabase.get(this).transcriptDao()))
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
