package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome at the logic layer (#561): when this fails, the user sees the lips bubble missing over a
 * focused field, lingering over one that left, or chasing a retry sequence after a teardown. It drives the
 * pure merge and decisions of a bubble field check; the accessibility calls themselves are not crossed on
 * the JVM (the device harness owns that boundary). Every expectation is a literal.
 */
class BubbleFieldCheckTest {

    @Test
    fun aMergedBurstAsksForEverythingAnyOfItsChecksAskedFor() {
        val transition = BubbleFieldCheck(transition = true, transitionUptime = 1_000L)
        val click = BubbleFieldCheck(revalidate = true)
        val retry = BubbleFieldCheck(retryOf = 4)
        val windows = BubbleFieldCheck(windowEvents = mapOf(7 to 1_500L, 9 to null))
        val laterWindows = BubbleFieldCheck(windowEvents = mapOf(9 to 1_700L, 11 to null, 7 to 1_200L))

        val merged = transition.merge(click).merge(retry).merge(windows).merge(laterWindows)

        assertEquals(
            BubbleFieldCheck(
                revalidate = true,
                transition = true,
                transitionUptime = 1_000L,
                retryOf = 4,
                windowEvents = mapOf(7 to 1_500L, 9 to 1_700L, 11 to null),
            ),
            merged,
        )
    }

    @Test
    fun theNewestRetryAttemptWinsAMerge() {
        assertEquals(6, BubbleFieldCheck(retryOf = 5).merge(BubbleFieldCheck(retryOf = 6)).retryOf)
        assertEquals(5, BubbleFieldCheck(retryOf = 5).merge(BubbleFieldCheck(revalidate = true)).retryOf)
    }

    @Test
    fun ourOwnOverlayMovingAsksForNothing() {
        // Re-checking on our own bubble's moves would let the bubble drive traversals of its own.
        val ownMove = BubbleFieldCheck(windowEvents = mapOf(42 to 10L))
        assertNull(ownMove.resolve(overlayWindowIds = setOf(42)))
        assertEquals(
            BubbleFieldCheck.Resolved(startsSequence = true, retryOf = null, transitionUptime = 10L),
            ownMove.resolve(overlayWindowIds = setOf(3)),
        )
    }

    @Test
    fun ourOwnOverlayMovingLaterDoesNotMoveARealTransitionsDeadlines() {
        // The retry offsets measure from the real transition, so an overlay event merged in after it must not
        // push them later (#561 review: the deadlines would then miss the ~2 s a returning editor takes).
        val merged = BubbleFieldCheck(transition = true, transitionUptime = 1_000L)
            .merge(BubbleFieldCheck(windowEvents = mapOf(42 to 1_900L)))
            .merge(BubbleFieldCheck(windowEvents = mapOf(8 to 1_400L)))
        assertEquals(
            BubbleFieldCheck.Resolved(startsSequence = true, retryOf = null, transitionUptime = 1_400L),
            merged.resolve(overlayWindowIds = setOf(42)),
        )
        assertEquals(1_900L, merged.resolve(overlayWindowIds = emptySet())?.transitionUptime)
    }

    @Test
    fun onlyALiveWindowEventCanStartASequence() {
        // The overlay's warranted change is dropped; the other window's cosmetic change only re-checks.
        val mixed = BubbleFieldCheck(windowEvents = mapOf(42 to 5L, 8 to null))
        assertEquals(BubbleFieldCheck.Resolved(startsSequence = false, retryOf = null), mixed.resolve(setOf(42)))
        // An unconditional ask survives an overlay-only event beside it.
        val clickAndOwnMove = BubbleFieldCheck(revalidate = true, windowEvents = mapOf(42 to 5L))
        assertEquals(BubbleFieldCheck.Resolved(startsSequence = false, retryOf = null), clickAndOwnMove.resolve(setOf(42)))
        assertEquals(BubbleFieldCheck.Resolved(startsSequence = false, retryOf = 2), BubbleFieldCheck(retryOf = 2).resolve(setOf(42)))
    }

    @Test
    fun aCancelledSequenceKeepsOnlyTheReCheckOfWhatIsOnScreen() {
        val waiting = BubbleFieldCheck(
            transition = true,
            transitionUptime = 99L,
            retryOf = 3,
            windowEvents = mapOf(5 to 98L, 6 to null),
        )
        assertEquals(
            BubbleFieldCheck(revalidate = true, windowEvents = mapOf(5 to null, 6 to null)),
            waiting.withoutSequence(),
        )
        assertEquals(BubbleFieldCheck(revalidate = true), BubbleFieldCheck(retryOf = 1).withoutSequence())
        assertTrue(BubbleFieldCheck().withoutSequence().isEmpty)
        assertFalse(BubbleFieldCheck(windowEvents = mapOf(1 to null)).isEmpty)
    }

    @Test
    fun anUnusableAnswerIsAskedAgainWholeUnlessItsSequenceWasCancelled() {
        val asked = BubbleFieldCheck(transition = true, transitionUptime = 50L, retryOf = 2)
        assertEquals(asked, asked.askAgain(startedIn = 2, generation = 2))
        assertEquals(BubbleFieldCheck(revalidate = true), asked.askAgain(startedIn = 2, generation = 3))
    }

    @Test
    fun anAttemptOfASupersededSequenceDoesNotTraverse() {
        assertEquals(BubbleFieldCheck(revalidate = true), BubbleFieldCheck(revalidate = true, retryOf = 2).withoutRetryOtherThan(3))
        assertTrue(BubbleFieldCheck(retryOf = 2).withoutRetryOtherThan(3).isEmpty)
        assertEquals(BubbleFieldCheck(retryOf = 3), BubbleFieldCheck(retryOf = 3).withoutRetryOtherThan(3))
    }

    @Test
    fun aTransitionTraversesUnlessASequenceAlreadyChasesThatWindow() {
        val transition = BubbleFieldCheck.Resolved(startsSequence = true, retryOf = null)
        assertTrue("no sequence runs", transition.discovers(focusedWindowId = 12, chasingWindowId = null))
        assertTrue("a sequence chases another window", transition.discovers(focusedWindowId = 12, chasingWindowId = 13))
        assertFalse("a sequence chases this window", transition.discovers(focusedWindowId = 12, chasingWindowId = 12))
        // A burst while the focused window is still unknown coalesces the same way (Codex review, 2026-09-13).
        assertFalse("unknown coalesces with unknown", transition.discovers(focusedWindowId = -1, chasingWindowId = -1))
        assertTrue("an unknown window with no sequence still looks", transition.discovers(focusedWindowId = -1, chasingWindowId = null))
    }

    @Test
    fun aRetryAttemptAlwaysTraversesAndAPlainReCheckNever() {
        val attempt = BubbleFieldCheck.Resolved(startsSequence = false, retryOf = 2)
        assertTrue(attempt.discovers(focusedWindowId = 12, chasingWindowId = 12))
        val recheck = BubbleFieldCheck.Resolved(startsSequence = false, retryOf = null)
        assertFalse(recheck.discovers(focusedWindowId = 12, chasingWindowId = null))
    }

    @Test
    fun aFoundFieldEndsTheSequenceWhateverElseTheCheckAsked() {
        val transition = BubbleFieldCheck.Resolved(startsSequence = true, retryOf = 4)
        assertEquals(DiscoveryStep.Stop, discoveryStep(true, transition, focusedWindowId = 1, chasingWindowId = 2, startedIn = 4, generation = 4))
        assertEquals(DiscoveryStep.Stop, discoveryStep(true, transition, focusedWindowId = 1, chasingWindowId = 2, startedIn = 3, generation = 4))
    }

    @Test
    fun anAnswerThatBeganBeforeACancelStartsAndContinuesNothing() {
        // A teardown (onUnbind, onInterrupt) or a field found the direct way bumped the generation while the
        // check ran: a torn-down service must never schedule a retry from an answer already in flight.
        val both = BubbleFieldCheck.Resolved(startsSequence = true, retryOf = 4)
        assertEquals(DiscoveryStep.Keep, discoveryStep(false, both, focusedWindowId = 1, chasingWindowId = null, startedIn = 4, generation = 5))
    }

    @Test
    fun aTransitionIntoAnUnchasedWindowRestartsAndIntoTheChasedOneKeeps() {
        val transition = BubbleFieldCheck.Resolved(startsSequence = true, retryOf = null)
        assertEquals(DiscoveryStep.Restart(9), discoveryStep(false, transition, focusedWindowId = 9, chasingWindowId = null, startedIn = 2, generation = 2))
        assertEquals(DiscoveryStep.Restart(9), discoveryStep(false, transition, focusedWindowId = 9, chasingWindowId = 8, startedIn = 2, generation = 2))
        assertEquals(DiscoveryStep.Keep, discoveryStep(false, transition, focusedWindowId = 9, chasingWindowId = 9, startedIn = 2, generation = 2))
        // A transition into a new window supersedes the attempt it was merged with.
        val merged = BubbleFieldCheck.Resolved(startsSequence = true, retryOf = 2)
        assertEquals(DiscoveryStep.Restart(9), discoveryStep(false, merged, focusedWindowId = 9, chasingWindowId = 8, startedIn = 2, generation = 2))
    }

    @Test
    fun anAttemptOfTheCurrentSequenceContinuesAndAnOldOneDoesNot() {
        assertEquals(
            DiscoveryStep.Continue,
            discoveryStep(false, BubbleFieldCheck.Resolved(false, retryOf = 7), focusedWindowId = 3, chasingWindowId = 3, startedIn = 7, generation = 7),
        )
        assertEquals(
            DiscoveryStep.Keep,
            discoveryStep(false, BubbleFieldCheck.Resolved(false, retryOf = 6), focusedWindowId = 3, chasingWindowId = 3, startedIn = 7, generation = 7),
        )
        assertEquals(
            DiscoveryStep.Keep,
            discoveryStep(false, BubbleFieldCheck.Resolved(false, retryOf = null), focusedWindowId = 3, chasingWindowId = 3, startedIn = 7, generation = 7),
        )
    }
}
