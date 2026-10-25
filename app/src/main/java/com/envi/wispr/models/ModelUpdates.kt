package com.envi.wispr.models

import android.content.Context
import com.envi.wispr.debug.DebugLogger

/**
 * #108: a version bump that pins a new model revision updates the model without the user asking.
 *
 * Runs once per main-process start, off the main thread, after #492's recovery. An update is owed when a previous
 * pinned revision is installed, the current one is not, and the user has not paused or cancelled that model's
 * delivery. It queues on the network `ModelDownloadPolicy` allows (Wi-Fi only by default) with KEEP, so a queued or
 * running download is never restarted. The previous model keeps dictating until the new one lands (`usableDescriptor`).
 */
internal object ModelUpdates {
    /** Free of Android; each clause is one #108 plan §3 and §17.2 settled. */
    fun owed(currentInstalled: Boolean, previousInstalled: Boolean, control: ModelDeliveryControlState): Boolean =
        !currentInstalled && previousInstalled && control == ModelDeliveryControlState.ACTIVE

    /** By receipts alone (no hashing): full verification still gates loading and admission. */
    fun owed(root: java.io.File, model: ModelDescriptor): Boolean {
        val store = ModelDeliveryStore(root)
        return owed(
            currentInstalled = store.installationStamp(model) != null,
            previousInstalled = model.previous.any { store.installationStamp(it) != null },
            control = ModelDeliveryControlStore(root).read(model),
        )
    }

    /** Call from a background coroutine; never throws. */
    suspend fun run(context: Context) {
        if (com.envi.wispr.device.DeviceTier.current == com.envi.wispr.device.DeviceTier.BLOCKED) return
        val root = ModelStorage.root(context)
        ModelManifest.all.forEach { model ->
            try {
                if (owed(root, model)) {
                    ModelDeliveryWorker.enqueueOwedUpdate(context, model)
                    DebugLogger.log(TAG, "Model update queued (${model.id})")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLogger.warn(TAG, "Model update check failed (${model.id}): ${error.javaClass.simpleName}")
            }
        }
    }

    private const val TAG = "ModelUpdates"
}
