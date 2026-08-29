package com.envi.wispr.models

import android.app.Application

/** Enqueues idempotent model bootstrap before any launcher or side-button activity can run. */
class ModelBootstrapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return
        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.parakeet)
        ModelDeliveryWorker.enqueueBootstrap(this, ModelManifest.s1)
    }
}
