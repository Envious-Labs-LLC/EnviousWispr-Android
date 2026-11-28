package com.envi.wispr.snippets

internal fun starterSnippets(): List<Snippet> {
    val created = System.currentTimeMillis() / 1000.0 - 978_307_200.0
    return listOf(
        Snippet("9F1C7A20-4E6B-4C31-9E2A-1D5B8F0A3C71", "my email", "john.doe@example.com", created),
        Snippet("3B84D0E5-6A17-4F92-8C0D-7E2A9B4F1D63", "my phone", "(555) 010-4477", created),
        Snippet("C6E29B14-8D3F-4A70-B5E8-2F91C7D04A56", "my address", "1600 Example Way, Suite 200, Springfield, IL 62704", created),
        Snippet("5A0D3F81-2C94-4E6D-A17B-8B36E5C920F4", "my calendar", "https://cal.example.com/john-doe", created),
        Snippet("72B4E8C0-1F5A-4D39-96C7-4A0E2D8B7F15", "my signature", "Thanks so much. John Doe, Product at Example Co.", created),
        Snippet("E8137C6A-9B02-4F58-8D41-6C3A5E90B2D7", "my intro", "Hi, I'm John Doe. I lead product at Example Co, and I'm happy to help however I can.", created),
    )
}

private val examples = starterSnippets().associateBy { it.id.lowercase() }

internal fun Snippet.isUneditedExample(): Boolean {
    val example = examples[id.lowercase()] ?: return false
    return example.trigger == trigger && example.expansion == expansion
}
