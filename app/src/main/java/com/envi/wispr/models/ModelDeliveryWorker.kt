package com.envi.wispr.models

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.envi.wispr.debug.DebugLogger
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
import kotlinx.coroutines.launch
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
            // #458: removing the speech model also frees the retired one, so the upgrade check stops fetching Ultra.
            SpeechModelUpgrade.afterRemove(root, model)
            ModelDeliveryNotification.clear(applicationContext, model)
            // Says so in its result: the control is cleared above, so the card tells a finished removal from a
            // finished download by reading this.
            return@withContext if (removed) Result.success(Data.Builder().putBoolean(KEY_REMOVED, true).build()) else failure("model removal failed")
        }
        // #449 chunk 2: a blocked phone downloads, adopts and repairs nothing; removal above still runs.
        if (com.envi.wispr.device.DeviceTier.current == com.envi.wispr.device.DeviceTier.BLOCKED) {
            return@withContext failure(UNSUPPORTED_PHONE)
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
        // Through the network WorkManager assigned (#108): a Wi-Fi-only download never falls over to mobile data.
        val result = store.download(model, HttpsRangeTransport(network), object : DownloadControl {
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
            DebugLogger.log(TAG, "Model source: ${model.id}/$file from ${ModelSourceHost.of(host).wire}")
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
            DownloadState.DOWNLOADING, DownloadState.VERIFYING -> failure(result.state.name, result.state)
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

    private class HttpsRangeTransport(private val network: android.net.Network?) : ModelTransport {
        override fun open(url: String, offset: Long): TransportResponse {
            var current = URI(url)
            repeat(4) {
                require(current.scheme == "https" && !current.host.isNullOrBlank()) { "model source must use HTTPS" }
                val url = URL(current.toString())
                val connection = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
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
        /** The failure reason on a phone `DeviceTier` blocks (#449). */
        internal const val UNSUPPORTED_PHONE = "unsupported phone"
        private const val TAG = "ModelDelivery"
        internal const val KEY_MODEL_ID = "model_id"
        internal const val KEY_REMOVE = "remove"
        /** Output of a finished removal, so the card offers Download rather than waiting on a download that never ran. */
        internal const val KEY_REMOVED = "removed"
        internal const val KEY_REPAIR = "repair"
        internal const val KEY_UPDATE = "update"
        internal const val KIND_TAG_PREFIX = "model-kind:"
        internal const val KIND_DOWNLOAD = "download"
        internal const val KIND_SETUP = "setup"
        internal const val KIND_UPDATE = "update"
        internal const val KIND_REPAIR = "repair"
        internal val KINDS = setOf(KIND_DOWNLOAD, KIND_SETUP, KIND_UPDATE, KIND_REPAIR)
        internal const val KEY_ADOPT_ONLY = "adopt_only"
        internal const val KEY_STATE = "state"
        internal const val KEY_BYTES = "bytes"
        internal const val KEY_TOTAL = "total"
        internal const val KEY_REASON = "reason"
        internal const val KEY_NO_LEGACY = "no_legacy_model"

        /** Bytes moved this attempt, as a closed bucket token: enough to tell a stall from a near-miss. */
        internal fun bytesBucket(bytes: Long): String = when {
            bytes <= 0L -> BYTES_NONE
            bytes < 10L * 1024 * 1024 -> BYTES_LT_10MB
            bytes < 100L * 1024 * 1024 -> BYTES_LT_100MB
            bytes < 500L * 1024 * 1024 -> BYTES_LT_500MB
            bytes < 1024L * 1024 * 1024 -> BYTES_LT_1GB
            else -> BYTES_GE_1GB
        }
        internal const val BYTES_NONE = "0"
        internal const val BYTES_LT_10MB = "lt_10mb"
        internal const val BYTES_LT_100MB = "lt_100mb"
        internal const val BYTES_LT_500MB = "lt_500mb"
        internal const val BYTES_LT_1GB = "lt_1gb"
        internal const val BYTES_GE_1GB = "ge_1gb"
        internal val BYTES_BUCKETS: Set<String> = setOf(BYTES_NONE, BYTES_LT_10MB, BYTES_LT_100MB, BYTES_LT_500MB, BYTES_LT_1GB, BYTES_GE_1GB)
        internal const val DOWNLOAD_PREFIX = "model-download-"
        internal const val ADOPT_PREFIX = "model-adopt-"

        internal fun downloadWorkName(model: ModelDescriptor): String = "$DOWNLOAD_PREFIX${model.id}"
        internal fun adoptionWorkName(model: ModelDescriptor): String = "$ADOPT_PREFIX${model.id}"

        /**
         * Every download, setup, update and repair takes its network from `ModelDownloadPolicy` (#108), so no path
         * can choose its own. Each request carries its kind as a tag, so a changed setting can re-queue it as itself.
         */
        internal suspend fun enqueue(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, KIND_DOWNLOAD)
        }

        /** Explicit setup action. KEEP makes recreation/repeated Get Started idempotent. */
        internal suspend fun enqueueSetup(context: Context, model: ModelDescriptor, restart: Boolean = false) {
            if (restart) ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            submit(context, model, KIND_SETUP, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        }

        internal suspend fun enqueueUpdate(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, KIND_UPDATE)
        }

        /**
         * An update owed at app start (#108): KEEP, so a queued or running download is never restarted, and the
         * user's Pause or Cancel is never cleared (the caller queues only for an ACTIVE control).
         */
        internal suspend fun enqueueOwedUpdate(context: Context, model: ModelDescriptor) {
            submit(context, model, KIND_UPDATE, ExistingWorkPolicy.KEEP)
        }

        internal suspend fun enqueueRepair(context: Context, model: ModelDescriptor) {
            enqueueDownload(context, model, KIND_REPAIR)
        }

        private val requests = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /** For callers with no coroutine (card buttons, the notification's Resume): the policy read suspends. */
        internal fun launch(block: suspend () -> Unit): kotlinx.coroutines.Job = requests.launch {
            try { block() } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                DebugLogger.warn(TAG, "Model request failed: ${error.javaClass.simpleName}")
            }
        }

        private suspend fun enqueueDownload(context: Context, model: ModelDescriptor, kind: String) {
            ModelDeliveryControlStore(ModelStorage.root(context)).clear(model)
            submit(context, model, kind, ExistingWorkPolicy.REPLACE)
        }

        private suspend fun submit(context: Context, model: ModelDescriptor, kind: String, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(downloadWorkName(model), policy, request(model, kind, ModelDownloadPolicy.anyNetwork(context, model)))
        }

        private fun request(model: ModelDescriptor, kind: String, anyNetwork: Boolean) = OneTimeWorkRequestBuilder<ModelDeliveryWorker>()
            .setInputData(Data.Builder().putString(KEY_MODEL_ID, model.id)
                .putBoolean(KEY_UPDATE, kind == KIND_UPDATE).putBoolean(KEY_REPAIR, kind == KIND_REPAIR).build())
            .setConstraints(ModelDownloadPolicy.constraints(anyNetwork))
            .addTag(KIND_TAG_PREFIX + kind)
            .build()

        /**
         * After the network rule changes (the setting, or a revision's "Download now"): a download still WAITING is
         * queued again as itself under the new rule. Its partial files stay on disk and the transfer resumes from them;
         * a running download already has its network, and a paused or cancelled one is left alone.
         */
        internal suspend fun reapplyNetwork(context: Context) = withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            val controls = ModelDeliveryControlStore(ModelStorage.root(context))
            ModelManifest.all.forEach { model ->
                if (controls.read(model) != ModelDeliveryControlState.ACTIVE) return@forEach
                val waiting = manager.getWorkInfosForUniqueWork(downloadWorkName(model)).get()
                    .firstOrNull { it.state == androidx.work.WorkInfo.State.ENQUEUED } ?: return@forEach
                val kind = waiting.tags.firstOrNull { it.startsWith(KIND_TAG_PREFIX) }?.removePrefix(KIND_TAG_PREFIX) ?: return@forEach
                if (kind !in KINDS) return@forEach
                manager.enqueueUniqueWork(downloadWorkName(model), ExistingWorkPolicy.REPLACE, request(model, kind, ModelDownloadPolicy.anyNetwork(context, model)))
            }
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

        internal suspend fun resume(context: Context, model: ModelDescriptor) {
            enqueue(context, model)
        }

        internal fun hasStaleInstallation(context: Context, model: ModelDescriptor): Boolean =
            ModelDeliveryStore(ModelStorage.root(context)).needsUpdate(model)
    }
}
