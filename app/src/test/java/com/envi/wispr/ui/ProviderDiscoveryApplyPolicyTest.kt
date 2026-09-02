package com.envi.wispr.ui

import com.envi.wispr.providers.Provider
import com.envi.wispr.ui.ProviderDiscoveryApplyPolicy.CacheAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails an older Check overwrites a newer list, a draft key's list is cached
 * under a credential that was never saved, or a saved key's cache survives the key being replaced.
 */
class ProviderDiscoveryApplyPolicyTest {
    @Test fun onlyTheLatestSequenceApplies() {
        assertTrue(ProviderDiscoveryApplyPolicy.isLatest(3, 3))
        assertFalse(ProviderDiscoveryApplyPolicy.isLatest(2, 3))
        assertFalse(ProviderDiscoveryApplyPolicy.isLatest(3, null))
    }

    @Test fun anotherProvidersCompletionCannotTouchTheActivePageAndADraftNeverMergesTheSavedCache() {
        assertTrue(ProviderDiscoveryApplyPolicy.appliesToActivePage(Provider.OPENAI, Provider.OPENAI))
        assertFalse(ProviderDiscoveryApplyPolicy.appliesToActivePage(Provider.OPENAI, Provider.GEMINI))
        assertFalse(ProviderDiscoveryApplyPolicy.appliesToActivePage(Provider.OPENAI, null))
        assertTrue(ProviderDiscoveryApplyPolicy.mergesWithCache(usedStoredKey = true))
        assertFalse(ProviderDiscoveryApplyPolicy.mergesWithCache(usedStoredKey = false))
    }

    @Test fun onlyAStoredKeyDiscoveryWritesTheCacheAtOnceAndNeverAnEmptyOne() {
        assertTrue(ProviderDiscoveryApplyPolicy.writesCacheNow(usedStoredKey = true, listedNonEmpty = true))
        assertFalse(ProviderDiscoveryApplyPolicy.writesCacheNow(usedStoredKey = false, listedNonEmpty = true))
        assertFalse(ProviderDiscoveryApplyPolicy.writesCacheNow(usedStoredKey = true, listedNonEmpty = false))
    }

    @Test fun afterSavePromotesOnlyTheMatchingSequenceClearsOnANewKeyAndDoesNothingOtherwise() {
        assertEquals(CacheAction.PROMOTE, ProviderDiscoveryApplyPolicy.afterSave(succeeded = true, suppliedKey = true, discoverySequence = 7, draftResultSequence = 7))
        assertEquals(CacheAction.CLEAR, ProviderDiscoveryApplyPolicy.afterSave(succeeded = true, suppliedKey = true, discoverySequence = 7, draftResultSequence = 6))
        assertEquals(CacheAction.CLEAR, ProviderDiscoveryApplyPolicy.afterSave(succeeded = true, suppliedKey = true, discoverySequence = null, draftResultSequence = null))
        assertEquals(CacheAction.NONE, ProviderDiscoveryApplyPolicy.afterSave(succeeded = true, suppliedKey = false, discoverySequence = 7, draftResultSequence = 7))
        assertEquals(CacheAction.NONE, ProviderDiscoveryApplyPolicy.afterSave(succeeded = false, suppliedKey = true, discoverySequence = 7, draftResultSequence = 7))
    }
}
