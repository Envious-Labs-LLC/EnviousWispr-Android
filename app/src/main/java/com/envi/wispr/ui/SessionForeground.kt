package com.envi.wispr.ui

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import com.envi.wispr.shortcuts.DictationNotificationController

/** Actual OS ownership, including a rejected type change during an existing take (#498). */
internal enum class ForegroundPromotion { ACTIVE, RETAINED_MICROPHONE, BACKGROUND_CONTINUATION, REFUSED }

/** Android boundary only. No cached eligibility or mutable foreground-success flag. */
internal interface ForegroundPlatform {
    val currentType: Int
    fun start(notification: Notification, type: Int)
    fun update(notification: Notification)
}

internal class AndroidForegroundPlatform(private val service: Service) : ForegroundPlatform {
    override val currentType: Int get() = service.foregroundServiceType
    override fun start(notification: Notification, type: Int) =
        service.startForeground(DictationNotificationController.NOTIFICATION_ID, notification, type)
    override fun update(notification: Notification) = DictationNotificationController.update(service, notification)
}

/** Notification failure cannot prevent recording stop or delivery of already recorded words. */
internal fun updateSessionNotification(platform: ForegroundPlatform, notification: () -> Notification) {
    try {
        platform.update(notification())
    } catch (error: Exception) {
        DebugSessionLog.warn("Session notification update failed: ${error.javaClass.simpleName}")
    }
}

internal fun promoteSessionForeground(
    platform: ForegroundPlatform,
    processing: Boolean,
    notification: () -> Notification,
): ForegroundPromotion {
    val requested = if (processing) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    if (platform.currentType == requested) {
        updateSessionNotification(platform, notification)
        return ForegroundPromotion.ACTIVE
    }
    val built = try {
        notification()
    } catch (error: Exception) {
        DebugSessionLog.warn("Session notification construction failed: ${error.javaClass.simpleName}")
        return if (processing) retainedForeground(platform) else ForegroundPromotion.REFUSED
    }
    try {
        platform.start(built, requested)
    } catch (_: ForegroundServiceStartNotAllowedException) {
        if (!processing) return ForegroundPromotion.REFUSED
        // This is the same take, with capture already closed. A rejected type change must not throw
        // away its audio. Read Android's actual state: background continuation is explicitly degraded
        // and may be stopped by the OS; the existing speech/polish bounds and teardown still apply.
        val outcome = retainedForeground(platform)
        DebugSessionLog.warn("Processing foreground change refused: $outcome")
        updateSessionNotification(platform) { built }
        return outcome
    }
    return ForegroundPromotion.ACTIVE
}

private fun retainedForeground(platform: ForegroundPlatform): ForegroundPromotion =
    if (platform.currentType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
        ForegroundPromotion.RETAINED_MICROPHONE
    } else {
        ForegroundPromotion.BACKGROUND_CONTINUATION
    }
