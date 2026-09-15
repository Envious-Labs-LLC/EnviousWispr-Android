package com.envi.wispr.ui

import com.envi.wispr.polish.S1Context
import com.envi.wispr.polish.S1Structure
import com.envi.wispr.polish.S1Styling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift Guard (#152): the Writing style card says what the Mac says, every chip has a name, and no
 * sentence carries a dash. When this fails, the two products' copy has drifted or a chip is blank.
 */
class S1ControlCopyTest {

    @Test fun everyChipHasADistinctLabel() {
        assertEquals(S1Styling.entries.size, S1Styling.entries.map(S1ControlCopy::label).toSet().size)
        assertEquals(S1Structure.entries.size, S1Structure.entries.map(S1ControlCopy::label).toSet().size)
        assertEquals(S1Context.entries.size, S1Context.entries.map(S1ControlCopy::label).toSet().size)
        for (line in S1ControlCopy.allStrings()) assertTrue(line, line.isNotBlank())
    }

    @Test fun theHintsAreTheMacsVerbatim() {
        assertEquals(
            "Semi-formal keeps capitals and full stops. Casual and semi-casual write the way you would text.",
            S1ControlCopy.STYLING_HINT,
        )
        assertEquals(
            "Lists turns a spoken run of items into bullet points. Prose keeps everything as sentences.",
            S1ControlCopy.STRUCTURE_HINT,
        )
        assertEquals(
            "Email lays out a greeting line and a sign-off block when you dictate them. It changes nothing else.",
            S1ControlCopy.CONTEXT_HINT,
        )
        assertEquals("Tone", S1ControlCopy.STYLING_LABEL)
    }

    @Test fun noUserFacingLineCarriesADash() {
        for (line in S1ControlCopy.allStrings()) assertFalse(line, line.contains('—') || line.contains('–'))
    }
}
