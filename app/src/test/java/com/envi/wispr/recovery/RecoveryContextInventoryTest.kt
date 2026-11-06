package com.envi.wispr.recovery

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.vocabulary.CustomTerm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/** Drift Guard: a new deterministic input field must force a context format/compatibility decision. */
class RecoveryContextInventoryTest {
    @Test fun addingAStoredWordOrCleanupFieldRequiresReviewingTheRecoveryFormat() {
        assertEquals(
            setOf("spelling", "aliases", "category", "priority", "forceReplace", "caseSensitive", "minSimilarityOverride", "usageCount", "imported"),
            instanceFields(CustomTerm::class.java),
        )
        assertEquals(
            setOf("removeFillers", "spokenEmoji", "spokenPunctuation", "englishSpelling", "spellingProtectedWords"),
            instanceFields(CleanupOptions::class.java),
        )
    }

    private fun instanceFields(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
}
