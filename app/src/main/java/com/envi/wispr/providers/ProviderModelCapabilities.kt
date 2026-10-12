package com.envi.wispr.providers

/**
 * Mac #3425's model-specific thinking policy, shared by polish and discovery requests.
 * Source: LLMModelCapabilities.swift at 8ccff73f7bd4caccd5934ab23ce098725982ff90.
 * Android uses OpenAI Responses, so Pro overrides follow the official model pages instead.
 * Unlisted models keep the fallback documented by each provider; future acceptance is unverified.
 */
internal object ProviderModelCapabilities {
    /** Complete optional field fragment for the Responses request, including its separating comma. */
    fun openAiFields(model: String): String {
        val id = model.lowercase()
        val base = ModelId.withoutIsoSnapshot(id) ?: id
        val effort = when (base) {
            "gpt-6-luna", "gpt-6-sol", "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol",
            "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.2" -> "none"
            // https://developers.openai.com/api/docs/models/gpt-5-pro (and each version's page).
            "gpt-5-pro" -> "high"
            "gpt-5.2-pro", "gpt-5.4-pro", "gpt-5.5-pro" -> "medium"
            else -> when {
                // Mac excludes these variants. Its low fallback is not evidence they accept low.
                id.contains("-pro") || id.contains("codex") -> null
                id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> "low"
                (id.startsWith("gpt-5") || id == "gpt-6" || id.startsWith("gpt-6-") || id.startsWith("gpt-6.")) &&
                    !id.contains("-chat") -> "low"
                else -> null
            }
        }
        return effort?.let { ",\"reasoning\":{\"effort\":${jsonString(it)}}" }.orEmpty()
    }

    /** Inner thinkingConfig fields, or null for an id without a verified control. */
    fun geminiThinkingConfig(model: String): String? = when (model.lowercase()) {
        "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite", "gemini-3.1-flash-lite-preview", "gemini-3-flash-preview" ->
            "\"thinkingLevel\":\"minimal\""
        "gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools" ->
            "\"thinkingLevel\":\"low\""
        "gemini-2.5-flash", "gemini-2.5-flash-lite" -> "\"thinkingBudget\":0"
        "gemini-2.5-pro" -> "\"thinkingBudget\":128"
        else -> null
    }

    /** Complete optional fields for Messages; thinking and effort are different Claude controls. */
    fun claudeFields(model: String): String {
        val id = model.lowercase()
        return when (ModelId.withoutSnapshot(id) ?: id) {
            "claude-sonnet-5-5" -> ",\"thinking\":{\"type\":\"between_tools\"}"
            "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1" -> ",\"output_config\":{\"effort\":\"low\"}"
            else -> ",\"thinking\":{\"type\":\"disabled\"}"
        }
    }
}
