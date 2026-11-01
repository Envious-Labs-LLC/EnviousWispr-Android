package com.envi.wispr.ui

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** Harness Contract: foreground refusal policy at the Android boundary, not OS eligibility evidence. */
class SessionForegroundTest {
    private class Platform(override var currentType: Int) : ForegroundPlatform {
        var starts = 0
        var updates = 0
        var failure: Exception? = null
        var updateFails = false
        override fun start(notification: Notification, type: Int) {
            starts++
            failure?.let { throw it }
            currentType = type
        }
        override fun update(notification: Notification) {
            updates++
            if (updateFails) throw SecurityException("synthetic notification denial")
        }
    }
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val processing = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val notification = Notification()

    @Test fun initialRefusalDoesNotClaimForegroundOrUpdateANonexistentNotification() {
        val platform = Platform(0).apply { failure = ForegroundServiceStartNotAllowedException("synthetic denial") }
        assertEquals(ForegroundPromotion.REFUSED, promoteSessionForeground(platform, false) { notification })
        assertEquals(1, platform.starts)
        assertEquals(0, platform.updates)
        assertEquals(0, platform.currentType)
    }
    @Test fun duplicateMicrophonePromotionOnlyUpdatesTheExistingNotification() {
        val platform = Platform(microphone).apply { failure = ForegroundServiceStartNotAllowedException("must not re-promote") }
        assertEquals(ForegroundPromotion.ACTIVE, promoteSessionForeground(platform, false) { notification })
        assertEquals(0, platform.starts)
        assertEquals(1, platform.updates)
    }
    @Test fun processingSwitchesTypeWhenAndroidAcceptsIt() {
        val platform = Platform(microphone)
        assertEquals(ForegroundPromotion.ACTIVE, promoteSessionForeground(platform, true) { notification })
        assertEquals(processing, platform.currentType)
        assertEquals(1, platform.starts)
    }
    @Test fun rejectedProcessingKeepsExistingMicrophoneOwnershipAndUpdatesNotification() {
        val platform = Platform(microphone).apply { failure = ForegroundServiceStartNotAllowedException("synthetic denial") }
        assertEquals(ForegroundPromotion.RETAINED_MICROPHONE, promoteSessionForeground(platform, true) { notification })
        assertEquals(microphone, platform.currentType)
        assertEquals(1, platform.updates)
    }
    @Test fun rejectedProcessingWithoutForegroundIsExplicitlyDegraded() {
        val platform = Platform(0).apply { failure = ForegroundServiceStartNotAllowedException("synthetic denial") }
        assertEquals(ForegroundPromotion.BACKGROUND_CONTINUATION, promoteSessionForeground(platform, true) { notification })
        assertEquals(0, platform.currentType)
        assertEquals(1, platform.updates)
    }
    @Test fun notificationDenialDoesNotUndoTheRetainedTake() {
        val platform = Platform(microphone).apply {
            failure = ForegroundServiceStartNotAllowedException("synthetic denial")
            updateFails = true
        }
        assertEquals(ForegroundPromotion.RETAINED_MICROPHONE, promoteSessionForeground(platform, true) { notification })
        assertEquals(1, platform.updates)
    }
    @Test fun initialNotificationConstructionFailureRefusesBeforePromotion() {
        val platform = Platform(0)
        val outcome = promoteSessionForeground(platform, false) { throw SecurityException("synthetic channel failure") }
        assertEquals(ForegroundPromotion.REFUSED, outcome)
        assertEquals(0, platform.starts)
        assertEquals(0, platform.updates)
    }
    @Test fun processingNotificationConstructionFailureRetainsTheExistingTake() {
        val platform = Platform(microphone)
        val outcome = promoteSessionForeground(platform, true) { throw SecurityException("synthetic channel failure") }
        assertEquals(ForegroundPromotion.RETAINED_MICROPHONE, outcome)
        assertEquals(microphone, platform.currentType)
        assertEquals(0, platform.starts)
    }
    @Test fun processingNotificationConstructionFailureWithoutForegroundIsDegraded() {
        val platform = Platform(0)
        val outcome = promoteSessionForeground(platform, true) { throw SecurityException("synthetic channel failure") }
        assertEquals(ForegroundPromotion.BACKGROUND_CONTINUATION, outcome)
        assertEquals(0, platform.starts)
    }
    @Test fun activeTypeSurvivesANotificationConstructionFailure() {
        val platform = Platform(microphone)
        assertEquals(ForegroundPromotion.ACTIVE,
            promoteSessionForeground(platform, false) { throw SecurityException("synthetic channel failure") })
        assertEquals(0, platform.starts)
        assertEquals(0, platform.updates)
    }

    @Test fun unrelatedPromotionErrorsAreNotReportedAsRecovered() {
        val failure = SecurityException("synthetic permission error")
        val platform = Platform(microphone).apply { this.failure = failure }
        try {
            promoteSessionForeground(platform, true) { notification }
            fail("unrelated failure was swallowed")
        } catch (actual: SecurityException) {
            assertSame(failure, actual)
        }
        assertEquals(0, platform.updates)
    }
}
