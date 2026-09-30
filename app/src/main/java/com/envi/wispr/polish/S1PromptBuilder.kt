package com.envi.wispr.polish

import kotlin.math.roundToInt

internal object S1PromptBuilder {
    private const val MAX_CUSTOM_WORDS = 50
    private const val MAX_CUSTOM_WORD_LENGTH = 50
    internal const val MAX_OUTPUT_TOKENS = 1024

    /**
     * The control line is the caller's choice, carried on the session's latched
     * [PolishPolicy.LocalS1]; this builder never picks a value itself (#152).
     *
     * S1-mini was trained with a closed control-line vocabulary. Custom terms are restored after
     * generation, so adding them to Context makes the prompt unsupported and less stable.
     */
    fun buildUserPrompt(rawText: String, control: S1ControlSettings): String =
        "${control.controlLine()}\n${rawText.trim()}"

    /**
     * The cap is a runaway guard, not a length target: the cooperative deadline bounds time. It
     * should not bind on a faithful answer. Prose costs about one token per 4 chars, but a spoken
     * number list costs about ONE TOKEN PER CHARACTER: measured on the S26 (build 236, 2026-09-30,
     * the engine's own `S1 generation ended` line) a 73 character count to twenty generated 73
     * tokens and stopped on its own (`stop=eos`). The old chars/3.5 estimate gave that take the
     * 64 token floor, so it would have ended after the seventeenth item (#385). The cap is one and
     * a fifth tokens per character plus headroom, floor 128, and never more than the context has left:
     * the model's window ([S1Config.CONTEXT_SIZE]) holds the prompt AND the answer, so the cap is held
     * to `CONTEXT_SIZE - (PROMPT_OVERHEAD_TOKENS + one token per input character)`. A list longer than
     * about 800 characters can still reach the cap, and the log says so (`reachedCap=true`).
     */
    fun maxOutputTokens(rawText: String): Int {
        val wanted = maxOf(rawText.length * TOKENS_PER_CHAR_WORST_CASE + HEADROOM_TOKENS, MIN_OUTPUT_TOKENS.toDouble())
        val roomLeft = S1Config.CONTEXT_SIZE - (PROMPT_OVERHEAD_TOKENS + rawText.length)
        return minOf(wanted, roomLeft.toDouble()).roundToInt().coerceIn(CRAMPED_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
    }

    private const val TOKENS_PER_CHAR_WORST_CASE = 1.2
    private const val HEADROOM_TOKENS = 64
    private const val MIN_OUTPUT_TOKENS = 128
    private const val CRAMPED_OUTPUT_TOKENS = 64

    // System prompt, control line and chat template around the input. Measured 2026-09-30: 151 prompt
    // tokens for a 73 character take, so 78 beyond the input at one token per character; 160 is the margin.
    private const val PROMPT_OVERHEAD_TOKENS = 160

    /** Legacy flat-word migration sanitizer. It does not participate in runtime matching. */
    fun sanitizeCustomWords(words: List<String>): List<String> = words
        .asSequence()
        .map { it.replace(Regex("[\\r\\n\\[\\]]"), " ").trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(MAX_CUSTOM_WORD_LENGTH) }
        .distinctBy { it.lowercase() }
        .take(MAX_CUSTOM_WORDS)
        .toList()

}
