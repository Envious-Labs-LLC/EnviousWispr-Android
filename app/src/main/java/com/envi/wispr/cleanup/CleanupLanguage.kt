package com.envi.wispr.cleanup

/**
 * What the app knows about the language of one dictation, and nothing more.
 *
 * Two cases, not a nullable string, because [Unknown] is a decision with its own contract rather than a
 * missing value: it means "nothing established the language, so behave exactly as an English-only build
 * did". Every consumer is then forced to say what it does when nothing is established, which is the case
 * that ships most often.
 *
 * Ported from macOS `DictationLanguageResolver`, whose `Resolution` carries the same abstention.
 */
sealed interface CleanupLanguage {

    /** Nothing established the language. Cleanup behaves exactly as it did before #107. */
    data object Unknown : CleanupLanguage

    /** A language established with enough confidence to act on. [code] is a base code, never a region tag. */
    data class Known(val code: String) : CleanupLanguage
}

/** What the detector said, with no policy applied. [code] is whatever tag the detector uses. */
data class DetectedLanguage(val code: String, val confidence: Float)

/**
 * Reads the language of a finished transcript. No policy, no thresholds: it reports what it thinks and
 * how sure it is, or null when it has no answer.
 *
 * A seam rather than a direct call, for the reason macOS gives for the same seam: a real detector cannot
 * be made to reproducibly land either side of a confidence floor across model versions, so the boundary
 * is tested through a fake, and the real one is measured on hardware rather than asserted here.
 */
fun interface LanguageDetector {
    fun detect(text: String): DetectedLanguage?
}

/**
 * Which English-shaped cleanup rules apply at each language state.
 *
 * The two questions below are the Android reading of the two gates macOS already ships:
 * `FillerRemovalStep.languageProtectedTokens` and `InverseTextNormalizationStep.skipReason(language:)`.
 * macOS keeps colliding filler tokens in one shared set and refuses them per language; this asks the
 * same question from the English side, because our shared set had `um` DELETED outright on 2026-09-02
 * (#107) and English users lost the commonest filler there is.
 */
object CleanupLanguagePolicy {

    /**
     * How sure the detector must be before its answer is acted on.
     *
     * **Measured on the S26 Ultra (SM-S948U1), debug build, 2026-09-03**, against 16 ASR-shaped samples
     * across nine languages, all lowercase and unpunctuated the way a transcript arrives. macOS also
     * uses 0.90, and that agreement is a coincidence worth stating rather than a citation: macOS's floor
     * is for `NLLanguageRecognizer` and these are ML Kit scores, a different quantity, so the number was
     * re-derived here rather than copied.
     *
     * What the run established. The detector named the correct language on **all 16**, so no sample
     * argued for a floor at all; the floor is doing one job, which is refusing answers it is not sure
     * of. Thirteen scored at or above 0.90 and every one was right. The three below were `um i think so`
     * at 0.60, `yes` at 0.80 and a three-word Polish sentence at 0.68 — the two English ones cost
     * nothing, because abstention and an English answer take the same path apart from the `um` token.
     *
     * **The named limit: a very short non-English sentence, roughly three words, may not clear this and
     * then keeps today's behaviour.** That is accepted rather than tuned away. Sixteen samples chosen by
     * the same person choosing the number cannot justify lowering it, which is the circular reasoning
     * macOS's own floor comment warns about, and the failure it would risk is the expensive direction:
     * English text labelled foreign loses its number formatting.
     *
     * The asymmetry that makes a high floor safe: below the floor the answer is [CleanupLanguage.Unknown],
     * which is today's shipped behaviour, so abstaining can never be a regression. Lower it only against
     * a corpus somebody else sourced.
     */
    const val MIN_CONFIDENCE = 0.90f

    /** Base codes treated as English. A region tag is reduced to its base before it reaches here. */
    private val english = setOf("en")

    /**
     * Extra filler tokens per language, and the ONLY place a language earns one.
     *
     * `um` is here for English and `err` deliberately is nowhere. `err` is an English VERB, so "To err is
     * human" became "To is human", and a confident English answer does not make it safe. It stays out at
     * every language state.
     */
    private val extrasByLanguage: Map<String, Set<String>> = mapOf("en" to setOf("um"))

    /**
     * Every set [extraFillers] can return, DERIVED from [extrasByLanguage] rather than listed beside it.
     *
     * `DeterministicCleanup` compiles one matcher per member and looks the matcher up with `getValue`, so
     * a state whose extras were not a member would throw instead of cleaning. A hand-listed population
     * could drift out of step with the map and no test would catch it, because a test can only sample
     * language codes and the codes are open (`workflow-process.md`
     * RULE: enumerate-from-the-producer-not-from-the-findings). Deriving closes that window at the type
     * instead of guarding it: adding a language to the map cannot fail to add its set here.
     */
    val allExtraFillerSets: Set<Set<String>> = extrasByLanguage.values.toSet() + setOf(emptySet())

    /** The detector's answer, reduced to a decision. Anything short of the floor abstains. */
    fun resolve(detected: DetectedLanguage?): CleanupLanguage {
        if (detected == null) return CleanupLanguage.Unknown
        if (!detected.confidence.isFinite() || detected.confidence < MIN_CONFIDENCE) return CleanupLanguage.Unknown
        val base = baseCode(detected.code) ?: return CleanupLanguage.Unknown
        return CleanupLanguage.Known(base)
    }

    /**
     * `pt-BR` to `pt`, `zh-Latn` to `zh`. Null for the ML Kit "undetermined" sentinel and for anything
     * that is not a language tag, so a sentinel can never become a `Known` language named "und".
     */
    fun baseCode(tag: String?): String? {
        val base = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (base.isEmpty() || base == "und") return null
        return base.takeIf { it.all(Char::isLetter) }
    }

    /** Tokens added to the shared filler set at this language state. */
    fun extraFillers(language: CleanupLanguage): Set<String> = when (language) {
        CleanupLanguage.Unknown -> emptySet()
        is CleanupLanguage.Known -> extrasByLanguage[language.code].orEmpty()
    }

    /**
     * Whether the English-shaped rewriting families are skipped entirely: spoken emoji, spoken
     * punctuation, the two unconditional article rewrites, and the whole structured pass that owns
     * numbers, money, dates, times, ordinals, email and URLs.
     *
     * True only for a language established as something other than English. macOS's `skipReason`
     * returns the same three answers on the same three inputs.
     */
    fun skipsEnglishRewrites(language: CleanupLanguage): Boolean = when (language) {
        CleanupLanguage.Unknown -> false
        is CleanupLanguage.Known -> language.code !in english
    }
}
