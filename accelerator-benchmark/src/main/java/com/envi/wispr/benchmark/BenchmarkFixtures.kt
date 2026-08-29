package com.envi.wispr.benchmark

internal data class BenchmarkFixture(
    val id: String,
    val rawText: String,
    val maxTokens: Int
)

internal object BenchmarkFixtures {
    const val SYSTEM_PROMPT =
        "You are a text normalizer for speech-to-text transcripts. The input begins with a control line specifying the styling, structure, and context settings; clean the transcript to match those settings and output only the cleaned text."

    private const val CONTROL_LINE =
        "[Styling: semi-formal] [Structure: prose] [Context: general]"

    val all = listOf(
        BenchmarkFixture(
            id = "short",
            rawText = "send the report tomorrow morning please",
            maxTokens = 64
        ),
        BenchmarkFixture(
            id = "medium",
            rawText = "hey team quick update um the mobile dictation build is ready for another test " +
                "please focus on punctuation numbers and whether the final sentence appears in the " +
                "original text field without changing the meaning",
            maxTokens = 96
        ),
        BenchmarkFixture(
            id = "long",
            rawText = "here is the project update first the recording experience is stable and the " +
                "transcription arrives quickly second the custom vocabulary system now stores a preferred " +
                "spelling together with spoken aliases third we are comparing the phone processor graphics " +
                "processor and neural processor using the same transcript and generation settings the goal " +
                "is not to win a synthetic speed test the goal is to reduce the time between stopping a " +
                "recording and seeing correct polished text in the original application while preserving " +
                "meaning punctuation names numbers and reliable fallback behavior",
            maxTokens = 192
        )
    )

    fun select(id: String): List<BenchmarkFixture> = when (id) {
        "all" -> all
        else -> all.filter { it.id == id }.ifEmpty {
            throw IllegalArgumentException("Unknown fixture: $id")
        }
    }

    fun userPrompt(fixture: BenchmarkFixture): String =
        "$CONTROL_LINE\n${fixture.rawText.trim()}"
}
