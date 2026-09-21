package com.envi.wispr.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.envi.wispr.debug.DebugLogger
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import com.envi.wispr.telemetry.AnalyticsEvent
import com.envi.wispr.telemetry.Telemetry
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
        setProgressAsync(Data.Builder().putString(KEY_STATE, DownloadState.VERIFYING.name).build())
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
            if (!legacy.isDirectory) return@withContext Result.success(Data.Builder().putBoolean(KEY_NO_LEGACY, true).build())
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
        val firstRun = !store.finalDirectory(model).exists()
        val startedAtMs = System.currentTimeMillis()
        if (StatFs(ModelStorage.root(applicationContext).path).availableBytes < required) {
            reportDelivery(model, DownloadState.FAILED, DeliveryFailureReason.DISK_FULL, ModelSourceHost.UNKNOWN, partial, startedAtMs, firstRun)
            return@withContext failure("not enough storage for ${model.displayName}")
        }
        var lastProgress = 0L
        var lastProgressTime = 0L
        var completedBytes = 0L
        var lastHost: String? = null
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
        }, onSource = { file, host ->
            // Which roof served the bytes (#168). The log, never the screen: a user does not choose hosts.
            DebugLogger.log(TAG, "Model source: ${model.id}/$file from $host")
            lastHost = host
        })
        reportDelivery(model, result.state, result.reason, ModelSourceHost.of(lastHost), completedBytes + result.bytes, startedAtMs, firstRun)
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

    /**
     * One `model_delivery.terminal` per attempt ending, never progress (issue #176, plan §3.1): the
     * model, the closed outcome and reason, which roof served, a byte bucket, the duration, and whether
     * this was the model's first delivery on this phone. Every ending is a breadcrumb; none is a defect.
     */
    private fun reportDelivery(
        model: ModelDescriptor,
        state: DownloadState,
        reason: DeliveryFailureReason?,
        host: ModelSourceHost,
        bytes: Long,
        startedAtMs: Long,
        firstRun: Boolean,
    ) {
        val durationSeconds = (System.currentTimeMillis() - startedAtMs) / 100 / 10.0
        Telemetry.breadcrumb(
            "model_delivery", "terminal",
            mapOf("model" to model.id, "outcome" to state.name.lowercase(), "reason" to reason?.wire, "source_host" to host.wire),
        )
        Telemetry.capture(
            AnalyticsEvent.ModelDeliveryTerminal(
                model = model.id,
                outcome = state.name.lowercase(),
                sourceHost = host.wire,
                reason = reason?.wire,
                bytesBucket = bytesBucket(bytes),
                durationSeconds = durationSeconds,
                firstRun = firstRun,
            ),
        )
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
                        if (offset == 0L && connection.responseCode == 206) { connection.disconnect(); throw ModelDeliveryException(DeliveryFailureReason.PARTIAL_RESPONSE, "unexpected partial response") }
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
                        val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw ModelDeliveryException(DeliveryFailureReason.REDIRECT_REFUSED, "redirect missing location") }
                        val next = current.resolve(location)
                        connection.disconnect()
                        if (next.scheme != "https" || next.userInfo != null || next.fragment != null || next.port !in listOf(-1, 443) || !allowedHost(current.host, next.host)) throw ModelDeliveryException(DeliveryFailureReason.REDIRECT_REFUSED, "unsafe model redirect")
                        current = next
                    }
                    else -> { connection.disconnect(); throw ModelDeliveryException(DeliveryFailureReason.HTTP_STATUS, "model source returned HTTP $responseCode") }
                }
            }
            throw ModelDeliveryException(DeliveryFailureReason.REDIRECT_REFUSED, "too many model redirects")
        }

        private fun allowedHost(from: String?, to: String?): Boolean = when {
            from == MODEL_HOST_OWN -> to == MODEL_HOST_OWN
            from == MODEL_HOST_HUGGING_FACE -> to == MODEL_HOST_HUGGING_FACE || to?.endsWith(".cdn.hf.co") == true
            from?.endsWith(".cdn.hf.co") == true -> to?.endsWith(".cdn.hf.co") == true
            else -> false
        }
    }

    companion object {
        private const val TAG = "ModelDelivery"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_REMOVE = "remove"
        const val KEY_REPAIR = "repair"
        const val KEY_UPDATE = "update"
        const val KEY_ADOPT_ONLY = "adopt_only"
        const val KEY_STATE = "state"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_REASON = "reason"
        const val KEY_NO_LEGACY = "no_legacy_model"

        /** Bytes moved this attempt, as a closed bucket token: enough to tell a stall from a near-miss. */
        fun bytesBucket(bytes: Long): String = when {
            bytes <= 0L -> "0"
            bytes < 10L * 1024 * 1024 -> "lt_10mb"
            bytes < 100L * 1024 * 1024 -> "lt_100mb"
            bytes < 500L * 1024 * 1024 -> "lt_500mb"
            bytes < 1024L * 1024 * 1024 -> "lt_1gb"
            else -> "ge_1gb"
        }
        private const val DOWNLOAD_PREFIX = "model-download-"
        private const val ADOPT_PREFIX = "model-adopt-"

        internal fun downloadWorkName(model: ModelDescriptor): String = "$DOWNLOAD_PREFIX${model.id}"
        internal fun adoptionWorkName(model: ModelDescriptor): String = "$ADOPT_PREFIX${model.id}"

        internal fun enqueue(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, update = false)
        }

        /** Explicit setup action. KEEP makes recreation/repeated Get Started idempotent. */
        internal fun enqueueSetup(context: Context, model: ModelDescriptor, mobileData: Boolean, restart: Boolean = false) {
            val root = ModelStorage.root(context)
            if (restart) ModelDeliveryControlStore(root).clear(model)
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id).build())
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(model),
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        internal fun enqueueUpdate(context: Context, model: ModelDescriptor) {
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

        internal fun enqueueRepair(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_REPAIR, true).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(model), ExistingWorkPolicy.REPLACE, request)
        }

        internal fun enqueueBootstrap(context: Context, model: ModelDescriptor) {
            val request = OneTimeWorkRequestBuilder<ModelDeliveryWorker>().setInputData(
                Data.Builder().putString(KEY_MODEL_ID, model.id).putBoolean(KEY_ADOPT_ONLY, true).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(adoptionWorkName(model), ExistingWorkPolicy.KEEP, request)
        }

        internal fun enqueueRemove(context: Context, model: ModelDescriptor) {
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

        internal fun cancel(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).write(model, ModelDeliveryControlState.CANCELLED)
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(downloadWorkName(model))
            manager.cancelUniqueWork(adoptionWorkName(model))
        }

        internal fun pause(context: Context, model: ModelDescriptor) {
            ModelDeliveryControlStore(ModelStorage.root(context)).write(model, ModelDeliveryControlState.PAUSED)
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(downloadWorkName(model))
            manager.cancelUniqueWork(adoptionWorkName(model))
        }

        internal fun resume(context: Context, model: ModelDescriptor) {
            enqueue(context, model)
        }

        internal fun hasStaleInstallation(context: Context, model: ModelDescriptor): Boolean =
            ModelDeliveryStore(ModelStorage.root(context)).needsUpdate(model)
    }
}
