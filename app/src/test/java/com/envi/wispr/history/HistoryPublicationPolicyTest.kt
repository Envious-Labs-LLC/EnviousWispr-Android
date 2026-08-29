package com.envi.wispr.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPublicationPolicyTest {
    @Test fun durableHistoryRowAllowsAutoInsert() {
        assertEquals(
            HistoryPublicationPolicy.Route.AUTO_INSERT,
            HistoryPublicationPolicy.route(persistedId = 42L, persistenceSucceeded = true),
        )
    }

    @Test fun persistenceFailureForcesCopyOnly() {
        assertEquals(
            HistoryPublicationPolicy.Route.COPY_ONLY,
            HistoryPublicationPolicy.route(persistedId = 0L, persistenceSucceeded = false),
        )
    }

    @Test fun invalidPersistedIdForcesCopyOnlyEvenWithoutException() {
        assertEquals(
            HistoryPublicationPolicy.Route.COPY_ONLY,
            HistoryPublicationPolicy.route(persistedId = 0L, persistenceSucceeded = true),
        )
    }
}
