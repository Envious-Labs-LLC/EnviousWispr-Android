package com.envi.wispr.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#220): the dictation's listeners belong to the connection that set them. The phone's
 * test client disconnecting never silences a dictation, a dictation's connection ending never silences the
 * test client, and a sign-up the app sends after its connection ended never reaches the next dictation.
 *
 * REVERTS: clear regardless of origin in `clearOwnedByLocked` (the cross-binding rows go red); drop the
 * `openEpochs` check in `register` (the late-registration rows go red); let the legacy unregister ignore
 * the origin (the unregister row goes red).
 */
class ListenerSlotsTest {
    /** A listener stand-in with its own identity, as a binder has. */
    private class Listener(val name: String)

    private val slots = ListenerSlots()
    private val slot = slots.slot<Listener> { it }

    @Test fun aTakeRegistrationSurvivesTheLegacyUnbind() {
        val epoch = slots.openTakeEpoch("take-a")
        val owner = Listener("owner")
        assertTrue(slot.register(SlotOrigin.Take(epoch), owner))
        slots.unbindLegacy()
        assertEquals("the test client's disconnect silenced the dictation", owner, slot.listener.get())
    }

    @Test fun aLegacyRegistrationIsClearedByTheLegacyUnbindAndSurvivesATakeUnbind() {
        val epoch = slots.openTakeEpoch("take-a")
        val client = Listener("client")
        assertTrue(slot.register(SlotOrigin.Legacy, client))
        slots.closeTakeEpoch("take-a")
        assertEquals("a dictation's disconnect silenced the test client", client, slot.listener.get())
        slots.unbindLegacy()
        assertNull(slot.listener.get())
        assertTrue(epoch > 0)
    }

    @Test fun aTakeRegistrationIsClearedByItsOwnUnbind() {
        val epoch = slots.openTakeEpoch("take-a")
        assertTrue(slot.register(SlotOrigin.Take(epoch), Listener("owner")))
        slots.closeTakeEpoch("take-a")
        assertNull("the dictation's listener outlived its connection", slot.listener.get())
    }

    @Test fun aLateRegistrationFromAnEndedBindingIsRefusedAndTheNextBindingKeepsItsListener() {
        val first = slots.openTakeEpoch("take-a")
        slots.closeTakeEpoch("take-a")
        val second = slots.openTakeEpoch("take-b")
        val next = Listener("next take")
        assertTrue(slot.register(SlotOrigin.Take(second), next))
        assertFalse("a late sign-up from an ended connection was accepted", slot.register(SlotOrigin.Take(first), Listener("late")))
        assertEquals(next, slot.listener.get())
        // And the late epoch cannot clear what the next binding set: its identifier is gone.
        slots.closeTakeEpoch("take-a")
        assertEquals(next, slot.listener.get())
    }

    @Test fun theLegacyUnregisterNeverClearsATakeRegistrationOfTheSameListener() {
        val epoch = slots.openTakeEpoch("take-a")
        val shared = Listener("same binder")
        slot.register(SlotOrigin.Take(epoch), shared)
        slot.unregisterLegacy(shared)
        assertEquals("the legacy unregister cleared the dictation's listener", shared, slot.listener.get())
        slot.register(SlotOrigin.Legacy, shared)
        slot.unregisterLegacy(Listener("another"))
        assertEquals("an unregister for another listener cleared this one", shared, slot.listener.get())
        slot.unregisterLegacy(shared)
        assertNull(slot.listener.get())
    }

    @Test fun aFailedPushClearsOnlyTheListenerItWasPushingToAndItsOrigin() {
        val epoch = slots.openTakeEpoch("take-a")
        val dead = Listener("dead")
        slot.register(SlotOrigin.Take(epoch), dead)
        val replacement = Listener("replacement")
        slot.register(SlotOrigin.Legacy, replacement)
        assertFalse("a stale clear erased a newer registration", slot.clearIfCurrent(dead))
        assertEquals(replacement, slot.listener.get())
        assertTrue(slot.clearIfCurrent(replacement))
        assertNull(slot.listener.get())
        // The origin went with it: the take's own unbind has nothing of its own to clear, and a new
        // legacy registration is not mistaken for the take's.
        val again = Listener("again")
        slot.register(SlotOrigin.Legacy, again)
        slots.closeTakeEpoch("take-a")
        assertEquals(again, slot.listener.get())
    }

    @Test fun destroyClearsEverySlot() {
        val other = slots.slot<Listener> { it }
        slot.register(SlotOrigin.Legacy, Listener("a"))
        other.register(SlotOrigin.Take(slots.openTakeEpoch("take-a")), Listener("b"))
        slots.clearAll()
        assertNull(slot.listener.get())
        assertNull(other.listener.get())
    }

    /**
     * A registration and the unbind of its binding race on two threads, released together, many times: the
     * slot ends either empty (the unbind won) or refusing that epoch forever after, and never holds a
     * listener whose epoch has closed. REVERT: drop the `openEpochs` check.
     */
    @Test fun aRegistrationRacingItsUnbindNeverOutlivesTheBinding() {
        repeat(500) { round ->
            val identifier = "race-$round"
            val epoch = slots.openTakeEpoch(identifier)
            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            val listener = Listener("racer $round")
            Thread { go.await(); slot.register(SlotOrigin.Take(epoch), listener); done.countDown() }.start()
            Thread { go.await(); slots.closeTakeEpoch(identifier); done.countDown() }.start()
            go.countDown()
            assertTrue("a racing thread never finished", done.await(5, TimeUnit.SECONDS))
            assertNull("round $round: a listener outlived its closed binding", slot.listener.get())
            assertFalse(slot.register(SlotOrigin.Take(epoch), listener))
        }
    }
}
