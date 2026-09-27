package com.envi.wispr.models

import android.content.Context

/**
 * Queues the background download of [ModelManifest.staged] on a phone that already dictates (#374 chunk 1).
 *
 * Runs off the main thread: [ModelStorage.isReady] hashes every model file and is restricted to a worker or a service
 * executor. It uses the existing [ModelDeliveryWorker.enqueueSetup] with `restart = false`, which is unique work with
 * KEEP, unmetered and storage-not-low, and which leaves the control store alone, so an app start never restarts a
 * download already running, and never resumes one the user paused or cancelled.
 */
internal object StagedModelTrigger {
    /** Queue only when the model in use is verified, the staged one is not, and the user has not stopped it. */
    fun shouldQueue(inUseReady: Boolean, stagedReady: Boolean, control: ModelDeliveryControlState): Boolean =
        inUseReady && !stagedReady && control == ModelDeliveryControlState.ACTIVE

    /** The whole decision over [staged], with every effect injected so a test can watch what is queued. */
    fun queuePending(
        inUseReady: Boolean,
        staged: List<ModelDescriptor>,
        ready: (ModelDescriptor) -> Boolean,
        control: (ModelDescriptor) -> ModelDeliveryControlState,
        enqueue: (ModelDescriptor) -> Unit,
    ) {
        staged.forEach { if (shouldQueue(inUseReady, ready(it), control(it))) enqueue(it) }
    }

    /** Call on an IO thread only. */
    fun run(context: Context) {
        val controls = ModelDeliveryControlStore(ModelStorage.root(context))
        queuePending(
            inUseReady = ModelStorage.isReady(context, ModelManifest.parakeet),
            staged = ModelManifest.staged,
            ready = { ModelStorage.isReady(context, it) },
            control = controls::read,
            enqueue = { ModelDeliveryWorker.enqueueSetup(context, it, mobileData = false) },
        )
    }
}
