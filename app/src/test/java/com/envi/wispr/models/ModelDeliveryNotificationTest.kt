package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #374 chunk 1: each deliverable model has its own fixed notification id and channel, so the staged speech model's
 * progress never replaces the speech or polish model's. MUTATION: drop the `parakeet-sq` case (its id then comes from a
 * hash and could collide).
 */
class ModelDeliveryNotificationTest {
    @Test fun everyDeliverableModelHasAFixedDistinctIdAndChannel() {
        assertEquals(43_001, ModelDeliveryNotification.notificationId(ModelManifest.parakeet))
        assertEquals(43_002, ModelDeliveryNotification.notificationId(ModelManifest.s1))
        assertEquals(43_003, ModelDeliveryNotification.notificationId(ModelManifest.parakeetSq))
        assertEquals("model_delivery_parakeet-sq", ModelDeliveryNotification.channelId(ModelManifest.parakeetSq))
        val ids = ModelManifest.deliverable.map(ModelDeliveryNotification::notificationId)
        assertEquals(ids.size, ids.toSet().size)
    }
}
