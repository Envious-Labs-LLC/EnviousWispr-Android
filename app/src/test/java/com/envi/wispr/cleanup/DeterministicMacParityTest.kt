package com.envi.wispr.cleanup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** Product Outcome: a spoken code, number or address arrives malformed when these rows fail. */
class DeterministicMacParityTest {
    @Test fun matchesMacCuratedAndHoldoutCorpora() {
        val corpora = linkedMapOf(
            "/cleanup/mac-parity.jsonl" to 2_114,
            "/cleanup/mac-parity-holdout.jsonl" to 3_881,
        )
        val failures = linkedMapOf<String, MutableList<String>>()
        var total = 0
        val overlays = checkNotNull(javaClass.getResourceAsStream("/cleanup/current-mac-overlays.jsonl"))
            .bufferedReader().useLines { lines ->
                lines.map { Json.parseToJsonElement(it).jsonObject }.toList()
                    .associateBy { it.getValue("corpus").jsonPrimitive.content to it.getValue("row").jsonPrimitive.content.toInt() }
            }
        val usedOverlays = mutableSetOf<Pair<String, Int>>()
        corpora.forEach { (corpus, expectedCount) ->
            var corpusTotal = 0
            val stream = checkNotNull(javaClass.getResourceAsStream(corpus)) { "Missing $corpus" }
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val row = Json.parseToJsonElement(line).jsonObject
                    val input = row.getValue("input").jsonPrimitive.content
                    val key = corpus.substringAfterLast('/') to corpusTotal
                    val overlay = overlays[key]
                    if (overlay != null) {
                        assertEquals("Overlay must describe the historical expectation", row.getValue("expected").jsonPrimitive.content, overlay.getValue("old_expected").jsonPrimitive.content)
                        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                        assertEquals("Overlay input identity changed", hash, overlay.getValue("input_sha256").jsonPrimitive.content)
                        usedOverlays += key
                    }
                    val expected = (overlay ?: row).getValue("expected").jsonPrimitive.content
                    val category = row.getValue("category").jsonPrimitive.content
                    val actual = DeterministicCleanup.apply(
                        input,
                        CleanupOptions(removeFillers = false, spokenEmoji = false, spokenPunctuation = true),
                    ).text
                    corpusTotal++
                    total++
                    if (actual != expected) {
                        failures.getOrPut(category) { mutableListOf() }
                            .add("$input => expected [$expected], actual [$actual]")
                    }
                }
            }
            assertEquals("Unexpected row count for $corpus", expectedCount, corpusTotal)
        }
        assertEquals("Unexpected combined parity count", 5_995, total)
        assertEquals("No overlay may silently stop applying", overlays.keys, usedOverlays)
        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Mac parity failures across $total rows:")
                    failures.forEach { (category, rows) ->
                        appendLine("$category: ${rows.size}")
                        rows.take(60).forEach { appendLine("  $it") }
                    }
                },
            )
        }
    }

    @Test fun matchesCurrentReferenceExtensionsWithTheirLanguageAndControls() {
        val failures = mutableListOf<String>()
        var count = 0
        checkNotNull(javaClass.getResourceAsStream("/cleanup/current-mac-features.jsonl"))
            .bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val row = Json.parseToJsonElement(line).jsonObject
                    val input = row.getValue("input").jsonPrimitive.content
                    val expected = row.getValue("expected").jsonPrimitive.content
                    val language = if (row.getValue("language").jsonPrimitive.content == "neutral") CleanupLanguage.Known("de") else CleanupLanguage.Unknown
                    val punctuation = row["punctuation"]?.jsonPrimitive?.content == "true"
                    val actual = DeterministicCleanup.apply(input, CleanupOptions(false, false, punctuation), language).text
                    if (actual != expected) failures += "${row["source"]}: [$input] expected [$expected], got [$actual]"
                    count++
                }
            }
        assertTrueNonEmptyReference(count)
        assertEquals(failures.take(30).joinToString("\n"), 0, failures.size)
    }

    private fun assertTrueNonEmptyReference(count: Int) {
        org.junit.Assert.assertTrue("Current reference input population was not loaded", count > 0)
    }
}
