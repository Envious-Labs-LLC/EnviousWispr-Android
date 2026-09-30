package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product Outcome (#385): the local log can say whether a cut-off answer hit the token cap or finished by itself. */
class GenerationEndTest {
    @Test fun anAnswerThatUsedTheWholeBudgetIsMarkedAsReachingTheCap() {
        assertTrue(GenerationEnd(stopReason = "length", generatedTokens = 84, promptTokens = 130, cap = 84).reachedCap)
        assertTrue(GenerationEnd(stopReason = "length", generatedTokens = 90, promptTokens = 130, cap = 84).reachedCap)
    }

    @Test fun anAnswerThatEndedBeforeTheBudgetIsNotMarkedAsReachingIt() {
        assertFalse(GenerationEnd(stopReason = "eos", generatedTokens = 40, promptTokens = 130, cap = 84).reachedCap)
    }

    @Test fun theLogLineCarriesTheEnginesStopWordAndCountsOnly() {
        assertEquals(
            "S1 generation ended: stop=eos generated=40 prompt=130 cap=84 reachedCap=false",
            GenerationEnd(stopReason = "eos", generatedTokens = 40, promptTokens = 130, cap = 84).logLine(),
        )
    }
}
