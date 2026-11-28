package com.envi.wispr.snippets

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Product outcomes, checked against the running Mac matcher and transfer document. */
class SnippetParityTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/snippets/$name")).bufferedReader().use { it.readText() }

    @Test fun macExportPreservesLiteralTextAndMatchesTheSameCommands() {
        val library = SnippetSerializer.decode(fixture("mac-v1.json"))
        assertEquals(library, SnippetSerializer.decode(SnippetSerializer.encode(library)))
        val cases = JSONArray(fixture("mac-delivery.json"))
        val values = SnippetFillIns(Date(0), Locale.US, TimeZone.getTimeZone("UTC"))
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val input = row.getString("input")
            val expansion = library.expand(input, values)
            val usesClipboard = row.getJSONArray("usedFillIns").toString().contains("clipboard")
            val expected = if (usesClipboard) input else row.getString("finalText")
            assertEquals(input, expected, expansion.restore(expansion.masked))
        }
    }

    @Test fun unavailableLongerTriggerDoesNotFireItsShorterPrefix() {
        val items = listOf(newSnippet("my email", "short"), newSnippet("my email address", "{{clipboard}}"))
        val result = SnippetLibrary(snippets = items).expand("backslash my email address and backslash my email")
        assertEquals("backslash my email address and short", result.restore(result.masked))
    }

    @Test fun dateAndTimeResolveWithoutReadingTheClipboard() {
        val fillIns = SnippetFillIns(Date(0), Locale.US, TimeZone.getTimeZone("UTC"))
        val actual = requireNotNull(fillIns.resolve("{{DATE}} at {{time}}"))
        assertEquals("Jan 1, 1970 at 12:00 AM", actual.replace('\u202F', ' ').replace('\u00A0', ' '))
    }

    @Test fun ambiguousPastedTextNeedsAChoiceAndUnquotedCsvKeepsItsQuote() {
        val empty = SnippetLibrary()
        val ambiguous = """sig = Hello,"Sam"""".trimIndent()
        val preview = SnippetTransfer.preview(ambiguous, SnippetFormat.AUTO, empty)
        assertEquals(ambiguous, preview.ambiguousInput)
        assertEquals("sig", preview.candidates.single().trigger)
        assertEquals("Hello,\"Sam\"", preview.candidates.single().expansion)
        val withHeader = "trigger,text,notes\nsig,Hello,work"
        assertEquals("Hello", SnippetTransfer.preview(withHeader, SnippetFormat.AUTO, empty).candidates.single().expansion)
        assertEquals("""Hello,"Sam"""", SnippetTransfer.preview(ambiguous, SnippetFormat.LIST, empty).candidates.single().expansion)
        val quotedList = "\"sig\": Hello,\"Sam\""
        assertEquals("Hello,\"Sam\"", SnippetTransfer.preview(quotedList, SnippetFormat.AUTO, empty).candidates.single().expansion)
        val mixed = ambiguous + "\n\"bye\",\"Goodbye\""
        val matched = SnippetTransfer.preview(mixed, SnippetFormat.AUTO, empty).candidates
        assertEquals(listOf("sig = Hello" to "Sam", "bye" to "Goodbye"), matched.map { it.trigger to it.expansion })
        assertEquals("5\" screen", SnippetTransfer.preview("screen,5\" screen", SnippetFormat.CSV, empty).candidates.single().expansion)
    }

    @Test fun oversizedManualSnippetIsRefusedBeforeItCanMakeAnUnrestorableBackup() {
        assertThrows(IllegalArgumentException::class.java) {
            SnippetLibrary().save(null, newSnippet("reply", "a".repeat(20_001)))
        }
    }

    @Test fun aDamagedOrDuplicatedMarkerCannotPublishChangedSavedText() {
        val result = SnippetLibrary(snippets = listOf(newSnippet("signature", "Best,\nSam\n"))).expand("backslash signature.")
        assertNotEquals("Best,\nSam\n", result.masked)
        assertEquals("Best,\nSam\n", result.restore(result.masked + "."))
        assertNull(result.restore("lost the marker"))
        assertNull(result.restore(result.masked + result.masked))
    }
}
