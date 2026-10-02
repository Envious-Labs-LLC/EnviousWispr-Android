package com.envi.wispr.ui

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.EnglishSpelling
import com.envi.wispr.polish.*
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: a default/legacy request silently loses the chosen spelling and saved words. */
class CleanupTransportTest {
    @Test fun theProductionProxySendsTheAppendedMethodWithTheCompleteSnapshot() {
        var seen: CleanupRequestOptions? = null
        var seenId = 0L
        var seenTake: String? = null
        val service = object : IPolishService.Stub() {
            override fun polish(raw: String?, fillers: Boolean, emoji: Boolean, punctuation: Boolean, callback: IPolishCallback?) = error("legacy")
            override fun polishRequest(id: Long, raw: String?, fillers: Boolean, emoji: Boolean, punctuation: Boolean, policy: PolishPolicy?, callback: IPolishCallback?) = error("legacy")
            override fun polishRequestForTake(id: Long, raw: String?, fillers: Boolean, emoji: Boolean, punctuation: Boolean, policy: PolishPolicy?, take: String?, callback: IPolishCallback?) = error("legacy method lost the snapshot")
            override fun polishRequestWithCleanupForTake(id: Long, raw: String?, cleanup: CleanupRequestOptions?, policy: PolishPolicy?, take: String?, callback: IPolishCallback?) {
                seen = cleanup; seenId = id; seenTake = take
                assertEquals("raw words", raw)
                assertEquals(PolishPolicy.Off, policy)
            }
            override fun isReady() = false
            override fun getStatus() = "unused"
            override fun warmUp() = Unit
            override fun warmUpWithPolicy(policy: PolishPolicy?) = Unit
            override fun cancel(id: Long) = Unit
            override fun isLocalModelReady() = false
            override fun localModelStatus() = "unused"
        }
        val options = CleanupOptions(false, false, true, EnglishSpelling.BRITISH, setOf("center", "kennedy"))
        val proxy = PipelineBindings.polishProxy(service) { it.run() }
        proxy.polishRequestWithCleanupForTake(413, "raw words", options, PolishPolicy.Off, "frozen-take", object : PolishListener {
            override fun onOutcome(outcome: PolishOutcome?) = Unit
            override fun onResult(text: String?, engine: String?, latencyMs: Long) = Unit
            override fun onError(message: String?) = Unit
        })
        assertEquals(options, checkNotNull(seen).options)
        assertEquals(413L, seenId)
        assertEquals("frozen-take", seenTake)
    }
}
