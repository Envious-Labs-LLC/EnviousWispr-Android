package com.envi.wispr.shortcuts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.envi.wispr.R
import com.envi.wispr.ui.DictationSessionService

object DictationNotificationController {
    private const val CHANNEL_ID = "active_dictation"
    const val NOTIFICATION_ID = 1001

    fun listening(context: Context): Notification {
        return build(
            context = context,
            title = "EnviousWispr is listening",
            detail = "Speak naturally. Stop or cancel at any time.",
            includeActions = true,
        )
    }

    fun processing(context: Context): Notification {
        return build(
            context = context,
            title = "Preparing your words",
            detail = "Transcribing and polishing locally on this phone.",
            includeActions = false,
        )
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun build(
        context: Context,
        title: String,
        detail: String,
        includeActions: Boolean,
    ): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wispr_mic)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (includeActions) {
            builder.addAction(
                R.drawable.ic_wispr_mic,
                "Stop",
                serviceIntent(
                    context,
                    REQUEST_STOP,
                    DictationSessionService.ACTION_STOP,
                ),
            )
            builder.addAction(
                R.drawable.ic_wispr_mic,
                "Cancel",
                serviceIntent(
                    context,
                    REQUEST_CANCEL,
                    DictationSessionService.ACTION_CANCEL,
                ),
            )
        }

        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active dictation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible controls while EnviousWispr is recording or processing"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun serviceIntent(
        context: Context,
        requestCode: Int,
        action: String,
    ): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            android.content.Intent(context, DictationSessionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_STOP = 11
    private const val REQUEST_CANCEL = 12
}
