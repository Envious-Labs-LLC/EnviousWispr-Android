package com.envi.wispr.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

/** Product Outcome: ordinary prose or an unsupported suffix must never become a broken link. */
class NeutralLinkOutcomeTest {
    private fun clean(text: String) = DeterministicCleanup.apply(text, CleanupOptions(false, false, false), CleanupLanguage.Known("fr")).text

    @Test fun allThreeSchemeHostAlternativesAndBothPrefixFormsConvertWhole() {
        val rows = listOf(
            "https dos puntos barra barra ejemplo punto es" to "https://ejemplo.es",
            "https://ejemplo punto es barra ayuda" to "https://ejemplo.es/ayuda",
            "https dos puntos barra barra localhost dos puntos 3000" to "https://localhost:3000",
            "https dos puntos barra barra localhost" to "https://localhost",
            "https dos puntos barra barra 192 punto 168 punto 1 punto 1" to "https://192.168.1.1",
            "192 punto 168 punto 1 punto 1 barra ayuda" to "192.168.1.1/ayuda",
            "localhost dos puntos 3000 barra api" to "localhost:3000/api",
            "www punto ejemplo punto es" to "www.ejemplo.es",
        )
        for ((input, expected) in rows) assertEquals(input, expected, clean(input))
    }

    @Test fun everyUnsupportedContinuationRefusesTheWholeLink() {
        val rows = listOf(
            "Je comprends le point de vue de Marie.",
            "ejemplo punto es barra api guion v2",
            "ejemplo punto es barra api signo de interrogación q",
            "https dos puntos barra barra ejemplo punto es barra api guion v2",
            "ejemplo punto es / ayuda",
            "ejemplo punto es barra api / v2",
            "www punto ejemplo punto es.foo",
            "www punto ejemplo punto es:8080",
            "ejemplo punto ai barra ayuda",
            "https://WWW.Example.COM/Case",
            "https dos puntos barra barra localhost dos puntos 99999",
        )
        for (input in rows) assertEquals(input, input, clean(input))
    }

    @Test fun aLongSupportedPathIsReadWholeAndDoesNotAcquireAnInventedSegmentBudget() {
        val input = "ejemplo punto es" + (1..10).joinToString("") { " barra part$it" }
        assertEquals("ejemplo.es" + (1..10).joinToString("") { "/part$it" }, clean(input))
        val unsupported = "$input guion v2"
        assertEquals(unsupported, clean(unsupported))
    }
}
