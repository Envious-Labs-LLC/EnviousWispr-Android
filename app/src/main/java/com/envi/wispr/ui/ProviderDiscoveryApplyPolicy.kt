package com.envi.wispr.ui

import com.envi.wispr.providers.Provider

/**
 * The pure decisions behind the live model list's state (#84): which completion is current, and what a
 * completed Save or Remove does to the per-provider cache. Kept out of the view model so they are tested
 * without an Android rig.
 */
object ProviderDiscoveryApplyPolicy {
    /** A completion applies only when its sequence is the latest allocated for that provider. */
    fun isLatest(sequence: Int, latestForProvider: Int?): Boolean = latestForProvider == sequence

    /** A completion touches the page's state only while that provider's page is the active one. */
    fun appliesToActivePage(completionProvider: Provider, activeProvider: Provider?): Boolean = activeProvider == completionProvider

    /** Only a stored-key discovery may borrow the saved credential's cached access; a draft key never does. */
    fun mergesWithCache(usedStoredKey: Boolean): Boolean = usedStoredKey

    /** A discovery with the STORED key is cached at once; one with a draft key waits for a matching Save. */
    fun writesCacheNow(usedStoredKey: Boolean, listedNonEmpty: Boolean): Boolean = usedStoredKey && listedNonEmpty

    /**
     * A save that supplies a key clears the provider's persisted cache BEFORE the write (#81): the commit and
     * the promotion are two steps, and a process death between them would otherwise restart with the old
     * key's cache labelled as the stored key's. A model-only save keeps the cache it will keep using.
     */
    fun clearsCacheBeforeSave(suppliedKey: Boolean): Boolean = suppliedKey

    enum class CacheAction { PROMOTE, CLEAR, NONE }

    /**
     * After a Save completes: promote the in-memory draft result whose sequence the page carried; clear
     * when a key was supplied without a matching sequence (a different credential is now stored); do
     * nothing on failure or when no key was supplied.
     */
    fun afterSave(succeeded: Boolean, suppliedKey: Boolean, discoverySequence: Int?, draftResultSequence: Int?): CacheAction = when {
        !succeeded -> CacheAction.NONE
        !suppliedKey -> CacheAction.NONE
        discoverySequence != null && discoverySequence == draftResultSequence -> CacheAction.PROMOTE
        else -> CacheAction.CLEAR
    }
}
