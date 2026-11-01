package com.envi.wispr.debug

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.paste.AutoPasteAvailability
import java.util.concurrent.CompletableFuture

/** Debug-only OS foreground fixture. It records no audio and never admits a dictation. */
internal class ForegroundProbeService : Service() {
    companion object {
        @Volatile var started = CompletableFuture<ForegroundProbeService>()
        @Volatile var destroyed = CompletableFuture<Unit>()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                DictationNotificationController.NOTIFICATION_ID,
                DictationNotificationController.listening(this, AutoPasteAvailability.LIVE, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            started.complete(this)
        } catch (error: Exception) {
            started.completeExceptionally(error)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        DictationNotificationController.dismiss(this)
        super.onDestroy()
        destroyed.complete(Unit)
    }
}
