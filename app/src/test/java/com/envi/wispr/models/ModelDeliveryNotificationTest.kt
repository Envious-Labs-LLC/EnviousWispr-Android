package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #374: each model has its own fixed notification id and channel, so the speech model's progress never replaces the
 * polish model's. MUTATION: drop the `parakeet-sq` case (its id then comes from a hash and could collide).
 */
class ModelDeliveryNotificationTest {
    @Test fun everyModelHasAFixedDistinctIdAndChannel() {
        assertEquals(43_001, ModelDeliveryNotification.notificationId(ModelManifest.parakeet))
        assertEquals(43_002, ModelDeliveryNotification.notificationId(ModelManifest.s1))
        assertEquals("model_delivery_parakeet-sq", ModelDeliveryNotification.channelId(ModelManifest.parakeet))
        val ids = ModelManifest.all.map(ModelDeliveryNotification::notificationId)
        assertEquals(ids.size, ids.toSet().size)
    }
}
