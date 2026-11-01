package com.envi.wispr.ui

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.debug.ForegroundProbeService
import com.envi.wispr.shortcuts.DictationNotificationController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness Contract: injected rejection after genuine Android foreground promotion. The production
 * adapter reads the real Service state; this is recovery coverage, not reproduction of Xiaomi's denial,
 * microphone capture, or end-to-end delivery. No test can set a foreground-success flag.
 */
@RunWith(AndroidJUnit4::class)
class SessionForegroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val processing = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun withProbe(action: (ForegroundProbeService, AndroidForegroundPlatform) -> Unit) {
        assertEquals("Grant microphone permission before this fixture", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals("Grant notifications so the same-ID update can be observed", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ActivityScenario.launch(SettingsActivity::class.java).use {
            ForegroundProbeService.started = CompletableFuture()
            ForegroundProbeService.destroyed = CompletableFuture()
            val intent = Intent(context, ForegroundProbeService::class.java)
            var requested = false
            try {
                awaitNotification("EnviousWispr is listening") {
                    context.startForegroundService(intent)
                    requested = true
                }
                val service = ForegroundProbeService.started.get(10, TimeUnit.SECONDS)
                main { assertEquals("Android actually granted microphone foreground", microphone, service.foregroundServiceType) }
                assertEquals("EnviousWispr is listening", notificationTitle()?.toString())
                action(service, AndroidForegroundPlatform(service))
            } finally {
                main { context.stopService(intent) }
                if (requested) ForegroundProbeService.destroyed.get(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun denied(real: AndroidForegroundPlatform): ForegroundPlatform = object : ForegroundPlatform by real {
        override fun start(notification: Notification, type: Int) {
            throw ForegroundServiceStartNotAllowedException("synthetic processing rejection")
        }
    }
    /** Android emits the notification event after posting; no immediate read or clock-based settle. */
    private fun awaitNotification(title: String, action: () -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val event = automation.executeAndWaitForEvent(
            { main(action) },
            { changed ->
                changed.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    changed.packageName?.toString() == context.packageName &&
                    (changed.parcelableData as? Notification)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            },
            10_000,
        )
        event.recycle()
    }

    /** The Android 16 emulator emitted no accessibility callback for foreground notification updates.
     * Observe exact OS state instead. The deadline can only fail, never stand in for success.
     */
    private fun awaitUpdatedTitle(expected: String, action: () -> Unit) {
        val manager = context.getSystemService(NotificationManager::class.java)
        main(action)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            val snapshot = manager.activeNotifications.filter { it.packageName == context.packageName }.map {
                Triple(it.id, it.tag, it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            }
            val current = snapshot.singleOrNull { it.first == DictationNotificationController.NOTIFICATION_ID && it.second == null }
            if (current?.third == expected) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw AssertionError("Notification title never became '$expected'; latest=$snapshot")
            // deadline-fallback: no update callback; throttle OS-state observations.
            Thread.sleep(minOf(50L, remaining))
        }
    }

    private fun notificationTitle(): CharSequence? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.single { it.id == DictationNotificationController.NOTIFICATION_ID }
        .notification.extras.getCharSequence(Notification.EXTRA_TITLE)

    @Test fun rejectedProcessingUsesRealRetainedForegroundAndUpdatesTheSameNotification() = withProbe { service, real ->
        awaitUpdatedTitle("Preparing your words") {
            val result = promoteSessionForeground(denied(real), true) { DictationNotificationController.processing(service) }
            assertEquals(ForegroundPromotion.RETAINED_MICROPHONE, result)
            assertEquals(microphone, service.foregroundServiceType)
        }
        assertEquals("Preparing your words", notificationTitle()?.toString())
    }

    @Test fun rejectedProcessingAfterForegroundWasRemovedIsNotClaimedAsForegroundSuccess() = withProbe { service, real ->
        main {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, service.foregroundServiceType)
            assertEquals(ForegroundPromotion.BACKGROUND_CONTINUATION,
                promoteSessionForeground(denied(real), true) { DictationNotificationController.processing(service) })
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, service.foregroundServiceType)
        }
    }

    @Test fun successfulProcessingChangesTheRealForegroundType() = withProbe { service, real ->
        awaitUpdatedTitle("Preparing your words") {
            assertEquals(ForegroundPromotion.ACTIVE,
                promoteSessionForeground(real, true) { DictationNotificationController.processing(service) })
            assertEquals(processing, service.foregroundServiceType)
        }
        assertEquals("Preparing your words", notificationTitle()?.toString())
    }

    @Test fun repeatedListeningDoesNotReRequestForegroundEligibility() = withProbe { service, real ->
        awaitUpdatedTitle("Preparing your words") {
            assertEquals(ForegroundPromotion.ACTIVE,
                promoteSessionForeground(denied(real), false) { DictationNotificationController.processing(service) })
            assertEquals(microphone, service.foregroundServiceType)
        }
    }
}
