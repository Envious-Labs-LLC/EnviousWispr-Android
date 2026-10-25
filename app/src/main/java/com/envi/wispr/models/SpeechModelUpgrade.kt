package com.envi.wispr.models

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import com.envi.wispr.debug.DebugLogger
import java.io.File

/**
 * #458: moves a phone that still holds the retired SmoothQuant speech model (`parakeet-sq`) to the current one.
 *
 * Runs once per main-process start, off the main thread. When the old model's folder is on disk, the current model is
 * not installed and the user has not paused or cancelled speech delivery, it enqueues the current model's download on
 * the network `ModelDownloadPolicy` allows (#108) with the storage-not-low constraint (`ModelDeliveryWorker.enqueueSetup`,
 * KEEP, so a running download is never restarted). It also retires the old id's leftovers that live outside the models folder: its two
 * unique work names and its notification channel. The old files themselves leave through `LegacyModelSweep`, after
 * the current model's first delivered result or the user's Remove.
 */
internal object SpeechModelUpgrade {
    const val RETIRED_ID = "parakeet-sq"

    /** The decision, free of Android: every clause is one the founder approved at Gate 2 (#458 plan §3.4). */
    fun owed(retiredFolderPresent: Boolean, currentInstalled: Boolean, currentControl: ModelDeliveryControlState): Boolean =
        retiredFolderPresent && !currentInstalled && currentControl == ModelDeliveryControlState.ACTIVE

    /** Call from a background thread; never throws. */
    suspend fun run(context: Context) {
        runCatching {
            val root = ModelStorage.root(context)
            val current = ModelManifest.parakeet
            val controls = ModelDeliveryControlStore(root)
            controls.inheritRetired(RETIRED_ID, current)
            // No hashing here: the cheap structural check answers "installed"; full verification still gates loading.
            val installed = ModelDeliveryStore(root).installationStamp(current) != null
            // A blocked phone (#449) downloads nothing; the worker would refuse the job at every start.
            val blocked = com.envi.wispr.device.DeviceTier.current == com.envi.wispr.device.DeviceTier.BLOCKED
            if (!blocked && owed(File(root, RETIRED_ID).isDirectory, installed, controls.read(current))) {
                ModelDeliveryWorker.enqueueSetup(context, current)
                DebugLogger.log(TAG, "Speech model upgrade queued")
            }
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(ModelDeliveryWorker.DOWNLOAD_PREFIX + RETIRED_ID)
            work.cancelUniqueWork(ModelDeliveryWorker.ADOPT_PREFIX + RETIRED_ID)
            context.getSystemService(NotificationManager::class.java)
                ?.deleteNotificationChannel(ModelDeliveryNotification.CHANNEL_PREFIX + RETIRED_ID)
        }.onFailure { DebugLogger.warn(TAG, "Speech model upgrade check failed: ${it.javaClass.simpleName}") }
    }

    /** After the speech model's Remove: the retired files go too, so the upgrade above stops firing (#458). */
    fun afterRemove(root: File, removed: ModelDescriptor): Long =
        if (removed == ModelManifest.parakeet) LegacyModelSweep.sweep(root) else 0L

    private const val TAG = "SpeechModelUpgrade"
}
