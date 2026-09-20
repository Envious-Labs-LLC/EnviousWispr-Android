package com.envi.wispr.models

import android.app.Application
import com.envi.wispr.telemetry.Telemetry

/** Enqueues idempotent model bootstrap before any launcher or side-button activity can run. */
class ModelBootstrapApplication : Application() {
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
