package com.envi.wispr.vocabulary

/** The same runtime defaults shipped by EnviousWispr on macOS. User terms win by spelling. */
object BuiltinVocabulary {
    val terms: List<CustomTerm> = listOf(
        CustomTerm(
            spelling = "EnviousWispr",
            aliases = listOf(
                "envious whisper", "envious wisper", "envious whispr",
                "envious visper", "envious cisper", "mbs cisper",
                "in vious wispr", "envy us wispr", "NVS Visper", "NBS Vesper",
            ),
            category = "brand",
        ),
        CustomTerm("Envious Labs", listOf("envious laps"), "brand"),
        CustomTerm("macOS", listOf("mac OS", "Mack OS"), "brand"),
        CustomTerm("iOS", listOf("I OS", "eye OS"), "brand"),
        CustomTerm("GitHub", listOf("git hub", "get hub"), "brand"),
        CustomTerm("ChatGPT", listOf("chat GPT", "chat G P T"), "brand"),
        CustomTerm("OpenAI", listOf("open AI", "open A I"), "brand"),
        CustomTerm("Claude", listOf("clod", "clawed"), "brand"),
        CustomTerm("API", listOf("A P I"), "acronym"),
        CustomTerm("CLI", listOf("C L I"), "acronym"),
        CustomTerm("VS Code", listOf("vs code", "vscode", "V S code"), "brand"),
    )

    fun withUserTerms(userTerms: List<CustomTerm>): List<CustomTerm> {
        val userSpellings = userTerms.map { it.spelling.trim().lowercase() }.toSet()
        // The Mac matcher resolves ordinary alias collisions with the later term.
        // Built-ins therefore load first so a user's saved term remains authoritative.
        return terms.filter { it.spelling.lowercase() !in userSpellings } + userTerms
    }
}
