package com.envi.wispr.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.CompletableFuture

/**
 * EXPERIMENT (exp/polish-copy-ahead), debug only: holds this process at foreground-service importance during a
 * benchmark, the same scheduling class a real dictation has (its session service is in the foreground). Without it
 * an instrumented benchmark runs in Android's background group, which on the S26 gets 4 of 8 cores.
 */
internal class BenchForegroundService : Service() {
    companion object {
        @Volatile var started = CompletableFuture<Unit>()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bench", "Benchmark", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, "bench")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Benchmark running").build()
        try {
            startForeground(0x5BE7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            started.complete(Unit)
        } catch (error: Exception) {
            started.completeExceptionally(error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
