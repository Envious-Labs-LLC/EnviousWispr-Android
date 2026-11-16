package com.envi.wispr.models

import android.content.Context
import com.envi.wispr.debug.DebugLogger
import java.io.File

/**
 * #531: moves a phone that still holds a retired EG-1 Micro file set ([RETIRED_REVISIONS]: the llama.cpp files, and the
 * first MNN export that only internal testers received) to the current revision on its own, with no tap (founder
 * 2026-11-15: "it should auto update and not require manual update").
 *
 * The revisions share the model id but not the file set (and the llama.cpp one not the engine), so
 * `ModelDescriptor.previous` cannot carry the llama.cpp one (it means "this engine still loads it", #108) and
 * `ModelUpdates` never sees it; one path serves both. Same shape as
 * `SpeechModelUpgrade` (#458): once per main-process start, off the main thread, when the old revision's receipt is in
 * EG-1's folder, the current revision is not installed and the user has not paused or cancelled EG-1's delivery, it
 * enqueues the update download on the network `ModelDownloadPolicy` allows (KEEP, so a running download is never
 * restarted). Admission then replaces the old folder (`ModelDeliveryStore` treats a folder that does not verify as
 * replaceable). Until it lands, EG-1 reads as not ready and polish falls back to the deterministic text.
 */
internal object Eg1ModelUpgrade {
    val RETIRED_REVISIONS = setOf("eg1-micro-1.0-g3d", "eg1-micro-1.0-g3d-mnn-hqq64")

    /** The decision, free of Android. */
    fun owed(retiredInstalled: Boolean, currentInstalled: Boolean, control: ModelDeliveryControlState): Boolean =
        retiredInstalled && !currentInstalled && control == ModelDeliveryControlState.ACTIVE

    /** Cheap, no hashing: the receipt's first line names the revision it was written for (`ModelDeliveryStore`). */
    internal fun retiredInstalled(root: File): Boolean {
        val receipt = File(File(root, ModelManifest.eg1Micro.id), ".verified-receipt")
        return receipt.isFile && runCatching { receipt.bufferedReader().use { it.readLine() } }.getOrNull() in RETIRED_REVISIONS
    }

    /** Call from a background thread; never throws. */
    suspend fun run(context: Context) {
        runCatching {
            val root = ModelStorage.root(context)
            val current = ModelManifest.eg1Micro
            val installed = ModelDeliveryStore(root).installationStamp(current) != null
            // A blocked phone (#449) downloads nothing; the worker would refuse the job at every start.
            val blocked = com.envi.wispr.device.DeviceTier.current == com.envi.wispr.device.DeviceTier.BLOCKED
            if (!blocked && owed(retiredInstalled(root), installed, ModelDeliveryControlStore(root).read(current))) {
                // KEEP, and the delivery control is left alone: a running download is never restarted, and a Pause or
                // Cancel that lands after the check above is never cleared (`enqueueUpdate` would do both).
                ModelDeliveryWorker.enqueueOwedUpdate(context, current)
                DebugLogger.log(TAG, "EG-1 Micro upgrade to the current revision queued")
            }
        }.onFailure { DebugLogger.warn(TAG, "EG-1 Micro upgrade check failed: ${it.javaClass.simpleName}") }
    }

    private const val TAG = "Eg1ModelUpgrade"
}
