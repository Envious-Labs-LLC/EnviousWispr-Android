package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Test

class RegexPolisherTest {
    @Test fun disabledFillerRemovalPreservesFiller() {
        assertEquals(
            "Um hello.",
            RegexPolisher.polish("um hello", removeFillers = false),
        )
    }

    @Test fun preservesMeaningfulPhrasesAndIntentionalRepetition() {
        assertEquals(
            "I like pizza very very much.",
            RegexPolisher.polish("I like pizza very very much"),
        )
        assertEquals(
            "I mean exactly what I said.",
            RegexPolisher.polish("I mean exactly what I said"),
        )
        assertEquals(
            "You know the answer.",
            RegexPolisher.polish("you know the answer"),
        )
    }
}
