package com.envi.wispr.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import java.io.IOException
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = ModelManifest.all.firstOrNull { it.id == inputData.getString(KEY_MODEL_ID) }
            ?: return@withContext failure("unknown model")
        val store = ModelDeliveryStore(ModelStorage.root(applicationContext))
        val root = ModelStorage.root(applicationContext)
        val controls = ModelDeliveryControlStore(root)
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            return@withContext failure("model storage is unavailable")
        }
        if (inputData.getBoolean(KEY_REMOVE, false)) {
            val removed = store.remove(model)
            controls.clear(model)
            ModelDeliveryNotification.clear(applicationContext, model)
            return@withContext if (removed) Result.success() else failure("model removal failed")
        }
        if (inputData.getBoolean(KEY_REPAIR, false)) {
            if (!store.repair(model)) return@withContext failure("model repair cleanup failed", DownloadState.REPAIR_NEEDED)
        }
        if (store.isVerified(model)) {
            controls.clear(model)
            ModelDeliveryNotification.clear(applicationContext, model)
            return@withContext Result.success()
        }
        when (controls.read(model)) {
            ModelDeliveryControlState.PAUSED -> {
                val status = DownloadStatus(DownloadState.PAUSED, 0L, model.files.sumOf { it.expectedBytes })
                setProgressAsync(Data.Builder().putString(KEY_STATE, status.state.name).putLong(KEY_TOTAL, status.total).build())
                ModelDeliveryNotification.notify(applicationContext, model, 0L, status.total, status.state)
                return@withContext Result.success(Data.Builder().putString(KEY_STATE, status.state.name).build())
            }
            ModelDeliveryControlState.CANCELLED -> {
                return@withContext failure("cancelled", DownloadState.CANCELLED)
            }
            ModelDeliveryControlState.ACTIVE -> Unit
        }
        // Migration reads only an app-owned external-files directory. Broad shared-storage
        // access is intentionally not requested by the normal manifest.
        val legacy = java.io.File(applicationContext.getExternalFilesDir(null), "models/${model.id}")
        if (inputData.getBoolean(KEY_ADOPT_ONLY, false)) {
            enterForeground(model, 0, model.files.sumOf { it.expectedBytes }, adoptionWorkName(model))
            val required = model.files.sumOf { it.expectedBytes } + 128L * 1024L * 1024L
            if (StatFs(root.path).availableBytes < required) {
                ModelDeliveryNotification.clear(applicationContext, model)
                return@withContext failure("not enough storage to adopt ${model.displayName}")
            }
            val adopted = store.adoptExisting(model, legacy)
            ModelDeliveryNotification.clear(applicationContext, model)
            if (adopted.state == DownloadState.READY) return@withContext Result.success()
            return@withContext failure(adopted.message ?: adopted.state.name)
        }
        val totalBytes = model.files.sumOf { it.expectedBytes }
        enterForeground(model, 0, totalBytes)
        val staging = java.io.File(root, ".${model.id}.download")
        val partial = model.files.sumOf { entry -> java.io.File(staging, entry.name + ".part").takeIf { it.isFile }?.length() ?: 0L }
        val required = (model.files.sumOf { it.expectedBytes } - partial).coerceAtLeast(0L) + 128L * 1024L * 1024L
        if (StatFs(ModelStorage.root(applicationContext).path).availableBytes < required) return@withContext failure("not enough storage for ${model.displayName}")
        var lastProgress = 0L
        var lastProgressTime = 0L
        var completedBytes = 0L
        val result = store.download(model, HttpsRangeTransport(), object : DownloadControl {
            override fun isStopped() = this@ModelDeliveryWorker.isStopped
            override fun isPaused() = controls.read(model) == ModelDeliveryControlState.PAUSED
            override fun isCancelled() = controls.read(model) == ModelDeliveryControlState.CANCELLED
        }, onProgress = { status ->
            val now = System.currentTimeMillis()
            if (status.state != DownloadState.DOWNLOADING || status.bytes - lastProgress >= 1024L * 1024L || now - lastProgressTime >= 500L) {
                val progressBytes = (completedBytes + status.bytes).coerceAtMost(totalBytes)
                lastProgress = status.bytes
                lastProgressTime = now
                setProgressAsync(
                    Data.Builder()
                        .putString(KEY_STATE, status.state.name)
                        .putLong(KEY_BYTES, progressBytes)
                        .putLong(KEY_TOTAL, totalBytes)
                        .putString(KEY_REASON, status.message.orEmpty())
                        .build(),
                )
                ModelDeliveryNotification.notify(applicationContext, model, progressBytes, totalBytes, status.state, status.message)
            }
            if (status.state == DownloadState.VERIFYING) completedBytes += status.bytes
        })
        when (result.state) {
            DownloadState.READY -> {
                controls.clear(model)
                ModelDeliveryNotification.notify(applicationContext, model, totalBytes, totalBytes, DownloadState.READY)
                Result.success(Data.Builder().putString(KEY_STATE, result.state.name).build())
            }
            DownloadState.PAUSED -> {
                ModelDeliveryNotification.notify(applicationContext, model, result.bytes, totalBytes, result.state)
                Result.success(Data.Builder().putString(KEY_STATE, result.state.name).build())
            }
            DownloadState.CANCELLED -> {
                ModelDeliveryNotification.notify(applicationContext, model, result.bytes, totalBytes, result.state, "cancelled")
                failure("cancelled", result.state)
            }
            DownloadState.REPAIR_NEEDED -> {
                ModelDeliveryNotification.notify(applicationContext, model, result.bytes, totalBytes, result.state, result.message)
                failure(result.message ?: "model integrity check failed", result.state)
            }
            DownloadState.FAILED -> {
                ModelDeliveryNotification.notify(applicationContext, model, result.bytes, totalBytes, result.state, result.message)
                failure(result.message ?: "model download failed", result.state)
            }
            else -> failure(result.state.name, result.state)
        }
    }

    private suspend fun enterForeground(
        model: ModelDescriptor,
        bytes: Long,
        total: Long,
        workName: String = downloadWorkName(model),
    ) {
        try {
            setForeground(
                androidx.work.ForegroundInfo(
                    ModelDeliveryNotification.notificationId(model),
                    ModelDeliveryNotification.build(applicationContext, model, bytes, total, workName = workName),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        } catch (_: SecurityException) {
            // Android 13 can hide notifications when POST_NOTIFICATIONS is denied. Work
            // still proceeds, and the notification is available in the task manager when
            // the platform permits it.
            ModelDeliveryNotification.notify(applicationContext, model, bytes, total, workName = workName)
        } catch (_: IllegalStateException) {
            ModelDeliveryNotification.notify(applicationContext, model, bytes, total, workName = workName)
        }
    }

    private fun failure(reason: String, state: DownloadState? = null): Result = Result.failure(
        Data.Builder()
            .putString(KEY_STATE, state?.name ?: DownloadState.FAILED.name)
            .putString(KEY_REASON, reason)
            .build(),
    )

    private class HttpsRangeTransport : ModelTransport {
        override fun open(url: String, offset: Long): TransportResponse {
            var current = URI(url)
            repeat(4) {
                require(current.scheme == "https" && !current.host.isNullOrBlank()) { "model source must use HTTPS" }
                val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                val responseCode = try { connection.responseCode } catch (error: IOException) { connection.disconnect(); throw error }
                when (responseCode) {
                    in 200..299 -> {
                        if (offset == 0L && connection.responseCode == 206) { connection.disconnect(); throw IOException("unexpected partial response") }
                        val resumed = offset > 0 && connection.responseCode == 206
                        val input = try {
                            connection.inputStream
                        } catch (error: IOException) {
                            connection.disconnect()
                            throw error
                        }
                        return TransportResponse(object : FilterInputStream(input) {
                            override fun close() { try { super.close() } finally { connection.disconnect() } }
                        }, resumed)
                    }
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw IOException("redirect missing location") }
                        val next = current.resolve(location)
                        connection.disconnect()
                        if (next.scheme != "https" || next.userInfo != null || next.fragment != null || next.port !in listOf(-1, 443) || !allowedHost(current.host, next.host)) throw IOException("unsafe model redirect")
                        current = next
                    }
                    else -> { connection.disconnect(); throw IOException("model source returned HTTP $responseCode") }
                }
            }
            throw IOException("too many model redirects")
        }

        private fun allowedHost(from: String?, to: String?): Boolean = when {
            from == "huggingface.co" -> to == "huggingface.co" || to?.endsWith(".cdn.hf.co") == true
            from?.endsWith(".cdn.hf.co") == true -> to?.endsWith(".cdn.hf.co") == true
            else -> false
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_REMOVE = "remove"
        const val KEY_REPAIR = "repair"
        const val KEY_UPDATE = "update"
        const val KEY_ADOPT_ONLY = "adopt_only"
        const val KEY_STATE = "state"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_REASON = "reason"
        private const val DOWNLOAD_PREFIX = "model-download-"
        private const val ADOPT_PREFIX = "model-adopt-"

        fun downloadWorkName(model: ModelDescriptor): String = "$DOWNLOAD_PREFIX${model.id}"
        fun adoptionWorkName(model: ModelDescriptor): String = "$ADOPT_PREFIX${model.id}"

        fun enqueue(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, update = false)
        }

        fun enqueueUpdate(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, update = true)
        }

        private fun enqueueDownload(context: Context, model: ModelDescriptor, update: Boolean) {
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_UPDATE, update).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(model), ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueueRepair(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_REPAIR, true).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(model), ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueueBootstrap(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>().setInputData(
                Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_ADOPT_ONLY, true).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(adoptionWorkName(model), ExistingWorkPolicy.REPLACE, request)
        }

        fun enqueueRemove(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).write(model, ModelDeliveryControlState.CANCELLED)
            val manager = WorkManager.getInstance(context)
            // Explicit cancellation makes removal win over a queued or running transfer.
            manager.cancelUniqueWork(downloadWorkName(model))
            manager.cancelUniqueWork(adoptionWorkName(model))
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_REMOVE, true).build())
                .build()
            manager.enqueueUniqueWork(downloadWorkName(model), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).write(model, ModelDeliveryControlState.CANCELLED)
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(downloadWorkName(model))
            manager.cancelUniqueWork(adoptionWorkName(model))
        }

        fun pause(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).write(model, ModelDeliveryControlState.PAUSED)
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(downloadWorkName(model))
            manager.cancelUniqueWork(adoptionWorkName(model))
        }

        fun resume(context: Context, model: ModelDescriptor) {
            enqueue(context, model)
        }

        fun hasStaleInstallation(context: Context, model: ModelDescriptor): Boolean =
            ModelDeliveryStore(ModelStorage.root(context)).needsUpdate(model)
    }
}
