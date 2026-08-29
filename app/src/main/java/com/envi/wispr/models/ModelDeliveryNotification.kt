package com.envi.wispr.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Low-importance, content-free progress notification for large local model transfers. */
object ModelDeliveryNotification {
    const val ACTION_CANCEL = "com.envi.wispr.models.CANCEL_DELIVERY"
    const val ACTION_PAUSE = "com.envi.wispr.models.PAUSE_DELIVERY"
    const val ACTION_RESUME = "com.envi.wispr.models.RESUME_DELIVERY"
    const val EXTRA_WORK_NAME = "work_name"
    const val EXTRA_MODEL_ID = "model_id"
    private const val CHANNEL_PREFIX = "model_delivery_"
    private const val NOTIFICATION_BASE = 43_000

    fun notificationId(model: ModelDescriptor): Int = NOTIFICATION_BASE + when (model.id) {
        "parakeet" -> 1
        "s1-mini" -> 2
        else -> (model.id.hashCode() and 0x7fff)
    }

    fun channelId(model: ModelDescriptor): String = "$CHANNEL_PREFIX${model.id}"

    fun build(
        context: Context,
        model: ModelDescriptor,
        bytes: Long,
        total: Long,
        state: DownloadState = DownloadState.DOWNLOADING,
        reason: String? = null,
        workName: String = ModelDeliveryWorker.downloadWorkName(model),
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId(model),
                "${model.displayName} model download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        val boundedTotal = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val boundedBytes = bytes.coerceIn(0L, total.coerceAtLeast(0L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val text = when (state) {
            DownloadState.VERIFYING -> "Verifying model files"
            DownloadState.READY -> "Model ready"
            DownloadState.REPAIR_NEEDED -> reason ?: "Model needs repair"
            DownloadState.FAILED -> reason ?: "Model download failed"
            DownloadState.CANCELLED -> "Download cancelled"
            else -> if (total > 0) "${formatBytes(bytes)} of ${formatBytes(total)}" else "Preparing download"
        }
        fun actionIntent(action: String) = Intent(context, ModelDeliveryCancelReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_WORK_NAME, workName)
            .putExtra(EXTRA_MODEL_ID, model.id)
        fun actionPendingIntent(action: String, requestCode: Int) = PendingIntent.getBroadcast(
            context,
            requestCode,
            actionIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelPendingIntent = actionPendingIntent(ACTION_CANCEL, notificationId(model) + 10)
        val transferAction = when (state) {
            DownloadState.PAUSED -> "Resume" to actionPendingIntent(ACTION_RESUME, notificationId(model) + 11)
            DownloadState.DOWNLOADING, DownloadState.VERIFYING -> "Pause" to actionPendingIntent(ACTION_PAUSE, notificationId(model) + 11)
            else -> null
        }
        return NotificationCompat.Builder(context, channelId(model))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${model.displayName}")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(state == DownloadState.DOWNLOADING || state == DownloadState.VERIFYING)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(boundedTotal, boundedBytes, total <= 0)
            .apply {
                transferAction?.let { addAction(0, it.first, it.second) }
                if (state != DownloadState.READY) addAction(0, "Cancel", cancelPendingIntent)
            }
            .build()
    }

    fun notify(
        context: Context,
        model: ModelDescriptor,
        bytes: Long,
        total: Long,
        state: DownloadState = DownloadState.DOWNLOADING,
        reason: String? = null,
        workName: String = ModelDeliveryWorker.downloadWorkName(model),
    ) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                notificationId(model),
                build(context, model, bytes, total, state, reason, workName),
            )
        }
    }

    fun clear(context: Context, model: ModelDescriptor) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(notificationId(model)) }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L * 1_024L) return "${bytes / 1_024L} KB"
        return String.format(Locale.US, "%.1f GB", bytes / (1_024.0 * 1_024.0 * 1_024.0))
            .takeIf { bytes >= 1_024L * 1_024L * 1_024L }
            ?: String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0))
    }
}

class ModelDeliveryCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val modelId = intent.getStringExtra(ModelDeliveryNotification.EXTRA_MODEL_ID)
        val model = ModelManifest.all.firstOrNull { it.id == modelId } ?: return
        when (intent.action) {
            ModelDeliveryNotification.ACTION_PAUSE -> ModelDeliveryWorker.pause(context, model)
            ModelDeliveryNotification.ACTION_RESUME -> ModelDeliveryWorker.resume(context, model)
            ModelDeliveryNotification.ACTION_CANCEL -> ModelDeliveryWorker.cancel(context, model)
        }
    }
}
