package com.envi.wispr.insertion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertionTextTest {
    @Test
    fun insertsAtCursorWithoutChangingExistingText() {
        assertEquals(
            "hello brave world",
            InsertionText.mergeAtSelection("hello world", "brave ", 6, 6),
        )
    }

    @Test
    fun replacesOnlyTheSelectedRange() {
        assertEquals(
            "hello Android",
            InsertionText.mergeAtSelection("hello world", "Android", 6, 11),
        )
    }

    @Test
    fun clampsInvalidSelectionWithoutOverwritingOtherText() {
        assertEquals(
            "hello world!",
            InsertionText.mergeAtSelection("hello world", "!", 99, 120),
        )
    }

    @Test
    fun smartInsertionCapitalizesAndSpacesAtDocumentStart() {
        val payload = InsertionText.smartPayload("world", "hello", 0, 0)

        assertEquals("Hello ", payload)
        assertEquals("Hello world", InsertionText.mergeAtSelection("world", payload, 0, 0))
    }

    @Test
    fun smartInsertionAddsOnlyTheMissingMiddleSeams() {
        val existing = "I think today"
        val payload = InsertionText.smartPayload(existing, "this", 7, 7)

        assertEquals(" this", payload)
        assertEquals("I think this today", InsertionText.mergeAtSelection(existing, payload, 7, 7))
    }

    @Test
    fun smartInsertionReplacementKeepsExistingOuterSpaces() {
        val existing = "hello old world"
        val payload = InsertionText.smartPayload(existing, "new", 6, 9)

        assertEquals("new", payload)
        assertEquals("hello new world", InsertionText.mergeAtSelection(existing, payload, 6, 9))
    }

    @Test
    fun smartInsertionDoesNotAddSpaceBeforeClosingPunctuation() {
        val existing = "I saw ."
        val payload = InsertionText.smartPayload(existing, "it", 6, 6)

        assertEquals("it", payload)
        assertEquals("I saw it.", InsertionText.mergeAtSelection(existing, payload, 6, 6))
    }

    @Test
    fun smartInsertionAddsTrailingSpaceAtFieldEnd() {
        assertEquals("done. ", InsertionText.smartPayload("Message: ", "done.", 9, 9))
    }

    @Test
    fun smartInsertionCapitalizesAfterANewLine() {
        assertEquals("Next ", InsertionText.smartPayload("First line\n", "next", 11, 11))
    }

    @Test
    fun smartInsertionKeepsContractionSeamsClosed() {
        assertEquals("t", InsertionText.smartPayload("don'", "t", 4, 4))
        assertEquals("don", InsertionText.smartPayload("'t", "don", 0, 0))
    }

    @Test
    fun smartInsertionDropsARepeatedWordAtTheLeftSeam() {
        val plan = InsertionText.smartPayloadPlan("see see", "see today", 7, 7)

        assertEquals(" today ", plan.text)
        assertTrue(plan.changesDictatedText)
    }

    @Test
    fun smartInsertionFailsOpenInsideAnExistingWord() {
        val plan = InsertionText.smartPayloadPlan("hello", "X", 2, 2)

        assertEquals("X", plan.text)
        assertFalse(plan.changesDictatedText)
    }

    @Test
    fun smartInsertionFailsOpenForAnInvalidSelection() {
        assertEquals("literal", InsertionText.smartPayload("hello", "literal", 99, 120))
        assertEquals("literal", InsertionText.smartPayload("hello", "literal", 4, 2))
    }

    @Test
    fun literalInsertionRemainsAvailableWhenSmartInsertionIsOff() {
        assertEquals(
            "helloworld",
            InsertionText.mergeAtSelection("world", "hello", 0, 0),
        )
    }
}
