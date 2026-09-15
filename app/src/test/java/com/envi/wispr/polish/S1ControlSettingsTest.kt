package com.envi.wispr.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Product outcome: the control line the model receives is exactly the user's picks in the trained
 * wire spelling. When a row here fails, the pick on the screen is not what reaches S1-mini.
 */
class S1ControlSettingsTest {

    @Test fun theDefaultIsTheLineTheAppShippedWith() {
        assertEquals(
            "[Styling: semi-formal] [Structure: lists] [Context: general]",
            S1ControlSettings.DEFAULT.controlLine(),
        )
    }

    @Test fun everyMemberOfEveryAxisComposesItsTrainedToken() {
        val expected = mapOf(
            S1Styling.CASUAL to "casual",
            S1Styling.SEMI_CASUAL to "semi-casual",
            S1Styling.SEMI_FORMAL to "semi-formal",
            S1Styling.FORMAL to "formal",
        )
        assertEquals(S1Styling.entries.toSet(), expected.keys)
        for ((styling, token) in expected) {
            assertEquals(
                "[Styling: $token] [Structure: lists] [Context: general]",
                S1ControlSettings(styling, S1Structure.LISTS, S1Context.GENERAL).controlLine(),
            )
        }
        assertEquals(
            "[Styling: semi-formal] [Structure: prose] [Context: general]",
            S1ControlSettings(S1Styling.SEMI_FORMAL, S1Structure.PROSE, S1Context.GENERAL).controlLine(),
        )
        assertEquals(
            "[Styling: semi-formal] [Structure: lists] [Context: email]",
            S1ControlSettings(S1Styling.SEMI_FORMAL, S1Structure.LISTS, S1Context.EMAIL).controlLine(),
        )
        assertEquals(listOf("prose", "lists"), S1Structure.entries.map { it.token })
        assertEquals(listOf("general", "email"), S1Context.entries.map { it.token })
    }

    @Test fun everyTokenRoundTripsAndAnythingElseIsTheDefault() {
        for (styling in S1Styling.entries) assertSame(styling, S1Styling.fromToken(styling.token))
        for (structure in S1Structure.entries) assertSame(structure, S1Structure.fromToken(structure.token))
        for (context in S1Context.entries) assertSame(context, S1Context.fromToken(context.token))
        assertSame(S1Styling.SEMI_FORMAL, S1Styling.fromToken(null))
        assertSame(S1Styling.SEMI_FORMAL, S1Styling.fromToken("Formal"))
        assertSame(S1Structure.LISTS, S1Structure.fromToken(""))
        assertSame(S1Context.GENERAL, S1Context.fromToken("slack"))
    }
}
