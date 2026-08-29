package com.envi.wispr.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLifecyclePolicyTest {
    @Test
    fun neverKeepsModelResident() {
        assertFalse(ModelLifecyclePolicy.shouldUnload(ModelUnloadPolicy.NEVER, Long.MAX_VALUE))
    }

    @Test
    fun unloadsOnlyAfterConfiguredIdleWindow() {
        assertFalse(ModelLifecyclePolicy.shouldUnload(ModelUnloadPolicy.AFTER_5_MINUTES, 299_999))
        assertTrue(ModelLifecyclePolicy.shouldUnload(ModelUnloadPolicy.AFTER_5_MINUTES, 300_000))
    }
}
