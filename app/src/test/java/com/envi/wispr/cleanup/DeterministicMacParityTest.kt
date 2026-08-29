package com.envi.wispr.cleanup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DeterministicMacParityTest {
    @Test fun matchesMacCuratedAndHoldoutCorpora() {
        val corpora = linkedMapOf(
            "/cleanup/mac-parity.jsonl" to 2_114,
            "/cleanup/mac-parity-holdout.jsonl" to 3_881,
        )
        val failures = linkedMapOf<String, MutableList<String>>()
        var total = 0
        corpora.forEach { (corpus, expectedCount) ->
            var corpusTotal = 0
            val stream = checkNotNull(javaClass.getResourceAsStream(corpus)) { "Missing $corpus" }
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val row = Json.parseToJsonElement(line).jsonObject
                    val input = row.getValue("input").jsonPrimitive.content
                    val expected = row.getValue("expected").jsonPrimitive.content
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
}
