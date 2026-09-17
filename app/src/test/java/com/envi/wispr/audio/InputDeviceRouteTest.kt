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
        val e = EffectiveDevice(InputRouteKind.BLUETOOTH, InputRouteReason.AUTO)
        assertEquals("", e.label())
        e.observe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
        e.observe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
        assertEquals("AirPods Pro 3", e.label())
        e.observe(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
        assertEquals("AirPods Pro 3, then Phone", e.label())
        assertEquals(InputRouteReason.AUTO.code, e.reasonCode())
        e.markRescued()
        assertEquals(InputRouteReason.RESCUED.code, e.reasonCode())
        assertTrue(e.wasRescued())
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
    fun aThrowingClearDoesNotStopTheListenerRemoval() {
        var removed = 0
        val hold = RouteHold({ throw IllegalStateException("binder gone") }, { removed++ })
        hold.markCommunicationSet()
        hold.markListenerSet()
        hold.release()
        assertEquals(1, removed)
        assertTrue(hold.isReleased)
    }

    // --- SilentRouteRescue: the bar, the retire, the one shot ---

    private val zeros = ByteArray(1_024)
    private val voice = ByteArray(1_024).also { it[3] = 5 }

    /** 96,000 bytes in 1,024-byte reads: the 94th read crosses (93 x 1,024 = 95,232 < 96,000). */
    private val readsToCross = 94

    @Test
    fun firesExactlyOnceWhenTheBarIsCrossedWithOnlyZeros() {
        val rescue = SilentRouteRescue(armed = true)
        repeat(readsToCross - 1) { assertFalse(rescue.offer(zeros, zeros.size)) }
        assertTrue("the read that crosses the bar fires", rescue.offer(zeros, zeros.size))
        assertFalse("one shot", rescue.offer(zeros, zeros.size))
    }

    @Test
    fun aPartialReadCountsItsBytesAndTheBarIsACrossing() {
        val rescue = SilentRouteRescue(armed = true)
        repeat(readsToCross - 2) { rescue.offer(zeros, zeros.size) } // 94,208 bytes
        assertFalse(rescue.offer(zeros, 1_000)) // 95,208
        assertTrue(rescue.offer(zeros, 1_000)) // 96,208 >= 96,000
    }

    @Test
    fun oneNonZeroSampleRetiresTheRescueForGood() {
        val rescue = SilentRouteRescue(armed = true)
        assertFalse(rescue.offer(voice, voice.size))
        repeat(readsToCross + 2) { assertFalse(rescue.offer(zeros, zeros.size)) }
    }

    @Test
    fun aNonZeroSampleAnywhereInTheReadRetires() {
        val rescue = SilentRouteRescue(armed = true)
        val tail = ByteArray(1_024).also { it[1_023] = 1 }
        assertFalse(rescue.offer(tail, tail.size))
        repeat(readsToCross + 2) { assertFalse(rescue.offer(zeros, zeros.size)) }
    }

    @Test
    fun anUnarmedRescueNeverFires() {
        val rescue = SilentRouteRescue(armed = false)
        repeat(readsToCross + 2) { assertFalse(rescue.offer(zeros, zeros.size)) }
    }

    @Test
    fun theBarIsThreeSecondsOfAudio() {
        assertEquals(96_000L, SilentRouteRescue.RESCUE_AFTER_BYTES)
    }
}
