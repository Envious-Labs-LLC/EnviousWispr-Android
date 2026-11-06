package com.envi.wispr.recovery

import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.vocabulary.CustomTerm
import java.util.Collections
import java.util.UUID

/** Effective deterministic inputs only. No editor, provider policy, credentials or matcher objects. */
internal class FrozenRecoveryContext private constructor(
    val takeId: UUID,
    val createdAtMs: Long,
    val recipeId: String,
    val formatProvenance: String,
    val cleanup: CleanupOptions,
    val terms: List<CustomTerm>,
) {
    companion object {
        /** Copy the successful matcher's actual inputs; pass an empty list after matcher fallback. */
        fun freeze(
            takeId: UUID,
            createdAtMs: Long,
            recipeId: String,
            formatProvenance: String,
            cleanup: CleanupOptions,
            effectiveTerms: List<CustomTerm>,
        ): FrozenRecoveryContext {
            RecoveryContextCodec.checkCollectionBounds(effectiveTerms, cleanup.spellingProtectedWords)
            return FrozenRecoveryContext(
                takeId, createdAtMs, recipeId, formatProvenance,
                cleanup.copy(spellingProtectedWords = Collections.unmodifiableSet(HashSet(cleanup.spellingProtectedWords))),
                Collections.unmodifiableList(effectiveTerms.map { term ->
                    term.copy(aliases = Collections.unmodifiableList(ArrayList(term.aliases)))
                }),
            )
        }
    }
}
