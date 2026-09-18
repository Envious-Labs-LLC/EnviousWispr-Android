package com.envi.wispr.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pick string, the effective-device record, the route hold and the silent-earbud rescue. */
class InputDeviceRouteTest {

    // --- InputDevicePick: garbage reads as Auto, a good string round-trips ---

    @Test
    fun aPickRoundTripsThroughItsString() {
        val pick = InputDevicePick.Device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "saurabh's airpod pro 3")
        assertEquals(pick, InputDevicePick.parse(pick.serialize()))
        assertEquals(InputDevicePick.Auto, InputDevicePick.parse(InputDevicePick.Auto.serialize()))
    }

    @Test
    fun aNameContainingTheSeparatorSurvives() {
        val pick = InputDevicePick.Device(7, "Odd|Name|Here")
        assertEquals(pick, InputDevicePick.parse(pick.serialize()))
    }

    @Test
    fun garbageReadsAsAutoNeverAsARefusal() {
        listOf(null, "", "  ", "auto", "|", "7|", "|name", "x|name", "seven").forEach { raw ->
            assertEquals("for '$raw'", InputDevicePick.Auto, InputDevicePick.parse(raw))
        }
    }

    // --- EffectiveDevice: the record the macOS bug lacked ---

    @Test
    fun theLabelListsEachDeviceOnceInOrder() {
        val e = EffectiveDevice(InputRouteReason.AUTO)
        assertEquals("", e.label())
        assertEquals("unknown until observed", InputRouteKind.NONE, e.kind)
        e.observe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
        assertEquals(InputRouteKind.BLUETOOTH, e.kind)
        e.observe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
        assertEquals("AirPods Pro 3", e.label())
        e.observe(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
        assertEquals("AirPods Pro 3, then Phone", e.label())
        assertEquals("the start kind is latched, not the latest", InputRouteKind.BLUETOOTH, e.kind)
        assertEquals(InputRouteReason.AUTO.code, e.reasonCode())
        assertEquals("the current kind is the latest observation", InputRouteKind.PHONE, e.currentKind)
    }

    @Test
    fun aRefusedBluetoothPreferenceThatStartsOnThePhoneIsAPhoneTake() {
        val e = EffectiveDevice(InputRouteReason.PREFERRED_REFUSED)
        e.observe(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
        assertEquals(InputRouteKind.PHONE, e.kind)
    }

    @Test
    fun theBuiltInMicrophoneIsAlwaysCalledPhoneWhateverTheModelSays() {
        assertEquals("Phone", InputDeviceLabels.labelFor(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1"))
        assertEquals("Bluetooth microphone", InputDeviceLabels.labelFor(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, ""))
    }

    // --- RouteHold: idempotent, undoes exactly what was set ---

    @Test
    fun releaseUndoesOnlyWhatWasSetAndOnlyOnce() {
        var cleared = 0
        var removed = 0
        val bare = RouteHold({ cleared++ }, { removed++ })
        bare.release()
        bare.release()
        assertEquals("a hold that set nothing clears nothing", 0, cleared)
        assertEquals(0, removed)
        assertTrue(bare.isReleased)

        val full = RouteHold({ cleared++ }, { removed++ })
        full.markCommunicationSet()
        full.markListenerSet()
        full.release()
        full.release()
        assertEquals(1, cleared)
        assertEquals(1, removed)
    }

    @Test
    fun givingBackTheLinkAloneKeepsListenerOwnershipForTheFinalRelease() {
        var cleared = 0
        var removed = 0
        val hold = RouteHold({ cleared++ }, { removed++ })
        hold.markCommunicationSet()
        hold.markListenerSet()
        hold.releaseCommunicationDevice()
        assertEquals("the link goes back at once", 1, cleared)
        assertEquals("the listener stays", 0, removed)
        assertFalse("the take is not over", hold.isReleased)
        hold.releaseCommunicationDevice()
        assertEquals("giving it back twice clears once", 1, cleared)
        hold.release()
        assertEquals("the final release removes the listener", 1, removed)
        assertEquals("and does not clear a link already given back", 1, cleared)
    }

    @Test
    fun aThrowingClearDoesNotStopTheListenerRemoval() {
        var removed = 0
        val hold = RouteHold({ throw IllegalStateException("binder gone") }, { removed++ })
        hold.markCommunicationSet()
        hold.markListenerSet()
        hold.release()
        assertEquals(1, removed)
        assertTrue(hold.isReleased)
    }
}
