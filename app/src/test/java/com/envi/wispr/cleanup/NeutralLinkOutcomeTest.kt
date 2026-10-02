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
            "2 punto 5 punto 0 barra ayuda",
            "localhost punto ai barra ayuda",
            "localhost punto app barra ayuda",
            "localhost punto xyz barra ayuda",
            "localhostdemo punto ai barra ayuda",
            "https://WWW.Example.COM/Case",
            "https dos puntos barra barra localhost dos puntos 99999",
        )
        for (input in rows) assertEquals(input, input, clean(input))
    }

    @Test fun mailboxFramesCannotConsumeARefusedAsciiTailOrAnUnsupportedContinuation() {
        val rows = listOf(
            "écris à jean dash dupont arobase gmail point com" to "écris à jean-dupont@gmail.com",
            "maría punto lópez arroba gmail punto com" to "maría.lópez@gmail.com",
            "mensagem ao arroba empresa.pt" to "mensagem ao arroba empresa.pt",
            "adres janapenstaartjevoorbeeld.it" to "adres janapenstaartjevoorbeeld.it",
            "mail infochiocciolaazienda.it punto com" to "mail infochiocciolaazienda.it punto com",
            "pismo info.собака.yandex.ru barra api" to "pismo info.собака.yandex.ru/api",
        )
        for ((input, expected) in rows) assertEquals(input, expected, clean(input))
    }

    @Test fun dashedCodesDoNotInheritTheDottedConsumersTrailingLinkPredicate() {
        assertEquals("La référence est B-2 barre oblique aide.", clean("La référence est B tiret 2 barre oblique aide."))
        assertEquals("2 punto 5 punto 0 barra ayuda", clean("2 punto 5 punto 0 barra ayuda"))
    }

    @Test fun unicodeWordAndSpaceClassesMatchAndroidsAlwaysUnicodeLibrary() {
        assertEquals(true, cleaningRegex("\\w+").matches("maría東京"))
        assertEquals(true, cleaningRegex("\\s+").matches("\u00A0\u2028"))
        assertEquals("la référence B-2", clean("la référence B trait d’union 2"))
        assertEquals("ejemplo punto es barra api point d’interrogation q", clean("ejemplo punto es barra api point d’interrogation q"))
    }

    @Test fun aLongSupportedPathIsReadWholeAndDoesNotAcquireAnInventedSegmentBudget() {
        val input = "ejemplo punto es" + (1..10).joinToString("") { " barra part$it" }
        assertEquals("ejemplo.es" + (1..10).joinToString("") { "/part$it" }, clean(input))
        val unsupported = "$input guion v2"
        assertEquals(unsupported, clean(unsupported))
    }
}
