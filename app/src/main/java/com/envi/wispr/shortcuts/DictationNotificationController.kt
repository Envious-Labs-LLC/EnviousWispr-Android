package com.envi.wispr.shortcuts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.envi.wispr.R
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.InsertionOutcomeMessages
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.ui.DictationSessionService

object DictationNotificationController {
    private const val CHANNEL_ID = "active_dictation"
    private const val RESULT_CHANNEL_ID = "dictation_results"
    const val NOTIFICATION_ID = 1001

    /**
     * A separate id, because `finishSession` dismisses 1001 within milliseconds of the fallback
     * decision. A result posted on the session notification would be erased before it was read.
     */
    const val RESULT_NOTIFICATION_ID = 1002

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

    /**
     * Says where the words went when automatic insertion did not reach the field. Dismissible and
     * silent: the user may already have seen the toast, and this is the copy they can come back to.
     *
     * **Takes a [FallbackAnnouncement] and nothing else, which is the point.** While this took a
     * `title` and a `detail`, any caller could post a durable notification saying one thing beside
     * a toast saying another, and a drift guard reading today's callers could not see the next one.
     * `FallbackAnnouncement`'s constructor is private to its own file and both of its factories
     * derive the toast and this notification in one call from the same inputs, so a contradiction
     * between the two surfaces is now unrepresentable rather than merely absent.
     *
     * It describes ONE dictation and it outlives that dictation's toast on purpose, so it has to be
     * retracted rather than left standing: see [dismissWordsNotInserted].
     */
    fun wordsNotInserted(context: Context, announcement: FallbackAnnouncement) {
        ensureResultChannel(context)
        val detail = announcement.notificationBody
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wispr_mic)
            .setContentTitle(announcement.notificationTitle)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(settingsIntent(context))
            .build()
        // POST_NOTIFICATIONS can be denied, and the toast has already carried the same sentence.
        runCatching {
            NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Retracts the claim [wordsNotInserted] made.
     *
     * It is a present-tense statement about the clipboard ("Press and hold the field, then tap
     * Paste"), and the clipboard is not ours: the next dictation overwrites it, a successful
     * insertion restores what preceded it, and the user copies over it. Left standing, it keeps
     * instructing the user to paste words the clipboard no longer holds, which is worse than
     * silence because they act on it.
     *
     * Deliberately EAGER rather than exact. Moving it later, to the moment a dictation actually
     * replaces the clipboard, would spare the user who starts a dictation and cancels it their lost
     * reminder, at the price of leaving a false instruction standing in every window before that
     * moment. Losing a reminder to words that are still on the clipboard and still in History costs
     * less than pasting the wrong text. A dictation that ends by posting its own claim needs
     * nothing here: the same notification id replaces it.
     *
     * The claim belongs to the dictation it describes, so the session owner ends it when the next
     * dictation begins. That is the only place every entry point passes through
     * (`architecture-rules.md` RULE: one-owner-for-the-session), and it is the last moment at which
     * the previous dictation is still the current one.
     *
     * A dictation is not the only thing that replaces the clipboard, so the other two callers are
     * the app's own successful clipboard writes: the History row's Copy button and the vocabulary
     * Export button, both in `ui/AppShell.kt`. Each of those has already put something ELSE where
     * this notification says the words are, and each knows whether its own write succeeded, so
     * leaving the claim standing there would send the user to paste a different transcript or a
     * vocabulary file.
     *
     * Those three are every replacement the app makes. What none of them covers is a clipboard
     * changed by ANOTHER app with no dictation in between: Android 10 and later deliver
     * `OnPrimaryClipChangedListener` only to the focused app and the default IME, so there is no
     * signal to close that on. That window stays open by platform limit, not by choice, and the
     * notification is dismissible and `autoCancel` for it.
     */
    fun dismissWordsNotInserted(context: Context) {
        NotificationManagerCompat.from(context).cancel(RESULT_NOTIFICATION_ID)
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

    private fun ensureResultChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                "Dictation results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description =
                    "Tells you where your words went when automatic insertion did not reach the field."
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /**
     * Opens the app, not Accessibility settings. Google requires a prominent disclosure immediately
     * before that hand-off and the app has none yet, so one more unguarded route would deepen an
     * existing Play-readiness gap (`content-brand.md`
     * RULE: the-accessibility-disclosure-is-policy-not-copy).
     *
     * There are three such routes in the UI today, all wired to the one
     * `Settings.ACTION_ACCESSIBILITY_SETTINGS` intent in `ui/SettingsActivity`: the Settings
     * "Auto-paste access" row, the onboarding step, and the Home
     * "Auto-paste is not connected" card. They are a known Play blocker, owned
     * by `.claude/knowledge/play-store-readiness.md`
     * RULE: the-accessibility-declaration-is-the-highest-review-risk. This notification is opened
     * from the shade with no screen in front of the user at all, which is the worst place of the
     * four to hand off from, so it stays pointed at the app.
     */
    private fun settingsIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            android.content.Intent(context, com.envi.wispr.ui.SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
