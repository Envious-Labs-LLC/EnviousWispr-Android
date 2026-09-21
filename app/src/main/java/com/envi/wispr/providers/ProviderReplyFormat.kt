package com.envi.wispr.providers

/**
 * Where each provider puts its answer, and how it says it finished (#189). One owner for both readings, shared
 * by the polish request and the discovery probe; every member is a decision, so a new format must declare
 * its own. The parsed `root` comes from [ProviderJson.parseOrNull].
 */
internal enum class ProviderReplyFormat {
    OPENAI_RESPONSES,
    OPENAI_CHAT,
    GEMINI,
    CLAUDE,
    OLLAMA,
    /** The key check: the body is judged by the adapter's list-envelope check, never parsed for text. */
    NONE,
    ;

    /**
     * The reply text a parsed body carries, trimmed, or null when it carries none.
     *
     * ONE owner for "where does this provider put its answer", used by `ProviderPolishClient.parseResponse` and by the model
     * probe (#104 review round 1). The probe used to look for a `"text"` label in the raw body, which is a
     * second reading of the same envelope and disagreed with this one in both directions: a multipart reply
     * whose FIRST part is empty reads as no answer, and a whitespace-only answer reads as an answer even
     * though polish rejects it. A model must not be offered or hidden on a judgement the polish path does
     * not share.
     *
     * The judgement that stays HERE and out of the probe is [ProviderPolishPrompt.isTranscriptOnly]: it
     * asks whether a polish reply is the transcript rather than commentary about it, and the probe sends
     * the word "Hi" rather than a transcript, so applying it would refuse working models.
     */
    fun replyText(root: Any?): String? = when (this) {
        OPENAI_RESPONSES -> root.firstMessageTextAt("output")
        OPENAI_CHAT -> root.stringAt("choices", 0, "message", "content")
        GEMINI -> root.firstTextAt("candidates", 0, "content", "parts")
        CLAUDE -> root.firstTextAt("content")
        OLLAMA -> root.stringAt("message", "content") ?: root.stringAt("response")
        NONE -> null
    }?.substringAfterLast("</think>")?.trim()

    /**
     * Did the model finish because it had finished, rather than because something stopped it?
     *
     * **Asked in the positive on purpose.** Listing the ways a reply can be cut short — an output cap, a
     * safety block, a recitation block, a language refusal, a tool-call fault — is a list that needs
     * extending whenever a provider adds a reason, and every missing entry silently condemns a working
     * model. Normal completion is ONE value per provider and providers do not add new ways to succeed.
     *
     * An absent, misspelt or unexpected marker therefore reads as "not proved", which is the safe answer
     * in both directions: the model stays on screen and is never chosen for the user.
     *
     * Exhaustive with no `else`, so a new response format must declare its own value.
     * `NONE` is the key check and never asks for text at all.
     */
    fun endedOfItsOwnAccord(root: Any?): Boolean = when (this) {
        // Measured 2026-09-02: gpt-4.1-mini answers `completed` with text at the probe's 16-token cap,
        // while gpt-5-mini and gpt-5-nano answer `incomplete` / `max_output_tokens` with none.
        OPENAI_RESPONSES -> root.stringAt("status") == "completed"
        OPENAI_CHAT -> root.stringAt("choices", 0, "finish_reason") == "stop"
        GEMINI -> root.stringAt("candidates", 0, "finishReason") == "STOP"
        // Anthropic ends a normal turn with `end_turn`, or with `stop_sequence` when one was matched. We
        // send no stop sequences, so only the first is reachable today; both are the model finishing.
        // Documented rather than measured: reaching it needs a Claude model that writes nothing at all,
        // and both models the founder's key reaches answered within the cap on 2026-09-02.
        CLAUDE -> root.stringAt("stop_reason") in setOf("end_turn", "stop_sequence")
        OLLAMA -> root.stringAt("done_reason") == "stop"
        NONE -> false
    }
}

private fun Any?.valueAt(vararg path: Any): Any? {
    var value: Any? = this
    for (part in path) {
        value = when (part) {
            is String -> (value as? Map<*, *>)?.get(part)
            is Int -> (value as? List<*>)?.getOrNull(part)
            else -> null
        }
        if (value == null) return null
    }
    return value
}

private fun Any?.stringAt(vararg path: Any): String? = valueAt(*path) as? String

private fun Any?.firstTextAt(vararg path: Any): String? =
    (valueAt(*path) as? List<*>)
        ?.asSequence()
        ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
        ?.firstOrNull { it.isNotEmpty() }

/**
 * OpenAI's Responses API `output` array holds typed items — `message`, `reasoning`, tool and
 * function calls among them — and a reasoning model can place a `reasoning` item before the
 * assistant's own `message` item. Reading a fixed index (`output[0]`) breaks the moment that
 * happens, silently, as `MALFORMED_RESPONSE`. This instead finds the first item whose `type` is
 * either absent (accepted for backward compatibility with a minimal payload shape) or exactly
 * `"message"`, skipping any other typed item ahead of it — matching OpenAI's own migration
 * guidance to iterate items by type rather than assume position (found and fixed in code review
 * on issue #62, 2026-09-01; full parser gap tracked separately as #65 for the remaining formats
 * this file does not touch here).
 */
private fun Any?.firstMessageTextAt(vararg path: Any): String? =
    (valueAt(*path) as? List<*>)
        ?.asSequence()
        ?.filterIsInstance<Map<*, *>>()
        ?.firstOrNull { item -> (item["type"] as? String)?.let { it == "message" } ?: true }
        ?.get("content")
        .firstTextAt()
