package com.envi.wispr.shortcuts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.envi.wispr.R
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.InsertionOutcomeMessages
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.polish.PolishFailureNotice
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.ui.SettingsActivity

object DictationNotificationController {
    private const val CHANNEL_ID = "active_dictation"
    const val NOTIFICATION_ID = 1001

    /** A polish that did not do its job (#77): its own channel, silent, and its own id beside the session's. */
    private const val POLISH_CHANNEL_ID = "polish_problems"
    const val POLISH_NOTIFICATION_ID = 1003

    /**
     * @param clipboard the user's frozen clipboard settings, or null when they have not been read
     * yet. Null is not a detail: on a cold start this notification is built before `AppPreferences`
     * has delivered anything, and a stand-in whose auto-copy default is `true` promised the
     * clipboard to users who had turned auto-copy off. The type carries the third state so the
     * sentence cannot be written from a value nobody decided.
     */
    fun listening(
        context: Context,
        autoPaste: AutoPasteAvailability,
        clipboard: ClipboardInsertionPolicy?,
    ): Notification {
        return build(
            context = context,
            title = "EnviousWispr is listening",
            detail = InsertionOutcomeMessages.listeningDetail(autoPaste, clipboard),
            includeStop = true,
            includeCancel = true,
        )
    }

    fun processing(context: Context): Notification {
        return build(
            context = context,
            title = "Preparing your words",
            detail = "Transcribing and polishing locally on this phone.",
            includeStop = false,
            includeCancel = true,
        )
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * The reason a polish did not do its job, in full (#77). A toast truncates after two lines on this
     * phone, so the sentence the user has to act on lives here: silent, not ongoing, cleared when tapped,
     * and tapping opens the app. Posting is best effort: without the notification permission the toast
     * and the History card still carry the outcome.
     */
    fun showPolishNotice(context: Context, notice: PolishFailureNotice) {
        ensurePolishChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, POLISH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wispr_mic)
            .setContentTitle(notice.title.trimEnd(':'))
            .setContentText(notice.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(POLISH_NOTIFICATION_ID, notification) }
    }

    private fun ensurePolishChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                POLISH_CHANNEL_ID,
                "AI Polish problems",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Why an AI polish did not run, and what to change"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun build(
        context: Context,
        title: String,
        detail: String,
        includeStop: Boolean,
        includeCancel: Boolean,
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

        if (includeStop) {
            builder.addAction(
                R.drawable.ic_wispr_mic,
                "Stop",
                serviceIntent(
                    context,
                    REQUEST_STOP,
                    DictationSessionService.ACTION_STOP,
                ),
            )
        }
        if (includeCancel) {
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
    private const val REQUEST_OPEN_APP = 13
}
