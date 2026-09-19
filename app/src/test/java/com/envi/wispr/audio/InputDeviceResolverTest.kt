package com.envi.wispr.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Auto order is the product decision (founder 2026-09-15: earbuds when connected, like a phone
 * call). When a row here fails, the user's earbuds are ignored or the wrong microphone records.
 */
class InputDeviceResolverTest {

    private var nextId = 100
    private fun device(type: Int, name: String, source: Boolean = true, sink: Boolean = false) =
        InputDeviceCandidate(id = nextId++, type = type, name = name, isSource = source, isSink = sink)

    private val phone = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
    private val phoneBack = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
    private val airpods = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
    private val buds = device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Galaxy Buds")
    private val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")
    private val usb = device(AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C headset")
    private val hearingAid = device(AudioDeviceInfo.TYPE_HEARING_AID, "Aid")
    private val telephony = device(AudioDeviceInfo.TYPE_TELEPHONY, "SM-S948U1")
    private val submix = device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "SM-S948U1")

    @Test
    fun autoPrefersEarbudsOverThePhone() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(phone, airpods, telephony, submix, phoneBack))
        assertSame(airpods, r.target)
        assertEquals(InputRouteReason.AUTO, r.reason)
        assertEquals(InputRouteKind.BLUETOOTH, r.kind)
    }

    @Test
    fun autoPrefersAWiredOrUsbHeadsetOverEarbuds() {
        assertSame(wired, InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(phone, airpods, wired)).target)
        assertSame(usb, InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(airpods, usb, phone)).target)
    }

    @Test
    fun autoPrefersLeAudioOverClassicWhenBothArePresent() {
        assertSame(buds, InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(phone, airpods, buds)).target)
    }

    @Test
    fun autoFallsBackToThePhoneWhenNothingElseCanRecord() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(telephony, phone, submix))
        assertSame(phone, r.target)
        assertEquals(InputRouteKind.PHONE, r.kind)
    }

    @Test
    fun autoNeverPicksAHearingAidButTheExplicitPickCan() {
        assertSame(phone, InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(hearingAid, phone)).target)
        val explicit = InputDeviceResolver.resolve(hearingAid.pick, listOf(hearingAid, phone))
        assertSame(hearingAid, explicit.target)
        assertEquals(InputRouteReason.PICKED, explicit.reason)
    }

    @Test
    fun autoWithoutBluetoothSkipsEarbudsForTheLinkRefusedRetry() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(airpods, phone), allowBluetooth = false)
        assertSame(phone, r.target)
    }

    @Test
    fun anExplicitBluetoothPickWhoseLinkWasRefusedFallsBackRatherThanRecordingSilence() {
        val r = InputDeviceResolver.resolve(airpods.pick, listOf(airpods, phone), allowBluetooth = false)
        assertSame(phone, r.target)
        assertEquals(InputRouteReason.PICK_MISSING, r.reason)
    }

    @Test
    fun anExplicitPickIsHonouredExactlyByTypeAndName() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1"), listOf(airpods, phone))
        assertSame(phone, r.target)
        assertEquals(InputRouteReason.PICKED, r.reason)
    }

    @Test
    fun aMissingPickFallsBackToAutoAndSaysSo() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3"), listOf(phone))
        assertSame(phone, r.target)
        assertEquals(InputRouteReason.PICK_MISSING, r.reason)
    }

    @Test
    fun aPickWhoseNameMatchesButTypeDiffersIsNotTheSameDevice() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Device(AudioDeviceInfo.TYPE_BLE_HEADSET, "AirPods Pro 3"), listOf(airpods, phone))
        assertEquals(InputRouteReason.PICK_MISSING, r.reason)
    }

    @Test
    fun nothingAtAllToRecordFromIsNullNotThePhone() {
        val r = InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(telephony, submix))
        assertNull(r.target)
        assertEquals(InputRouteKind.NONE, r.kind)
    }

    @Test
    fun aPortNobodyCanSpeakIntoIsNeverPickedEvenExplicitly() {
        // The S26 lists its telephony port and the remote submix as sources (probe, 2026-09-16).
        val r = InputDeviceResolver.resolve(telephony.pick, listOf(telephony, submix, phone))
        assertSame(phone, r.target)
        assertEquals(InputRouteReason.PICK_MISSING, r.reason)
        assertEquals(listOf(phone), InputDeviceResolver.pickable(listOf(telephony, submix, phone, phoneBack)))
    }

    @Test
    fun sinksAreNeverRecordingTargets() {
        val sinkOnly = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3", source = false, sink = true)
        assertSame(phone, InputDeviceResolver.resolve(InputDevicePick.Auto, listOf(sinkOnly, phone)).target)
    }

    @Test
    fun theCommunicationSinkIsMatchedByNameAndTheSameTransport() {
        val sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", source = false, sink = true)
        val le = device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds", source = false, sink = true)
        val a2dp = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds", source = false, sink = true)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-S948U1", source = false, sink = true)
        val scoMic = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        val leMic = device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds")
        // Both sink orderings: the transport, not the list order, decides.
        assertSame(sco, InputDeviceResolver.communicationSinkFor(scoMic, listOf(speaker, a2dp, le, sco)))
        assertSame(sco, InputDeviceResolver.communicationSinkFor(scoMic, listOf(sco, le)))
        assertSame(le, InputDeviceResolver.communicationSinkFor(leMic, listOf(speaker, a2dp, sco, le)))
        assertSame(le, InputDeviceResolver.communicationSinkFor(leMic, listOf(le, sco)))
        assertNull(InputDeviceResolver.communicationSinkFor(scoMic, listOf(speaker, a2dp, le)))
    }

    @Test
    fun onlyBluetoothTargetsNeedTheRoutingCalls() {
        assertEquals(true, InputDeviceResolver.needsBluetoothRoute(airpods))
        assertEquals(true, InputDeviceResolver.needsBluetoothRoute(buds))
        assertEquals(false, InputDeviceResolver.needsBluetoothRoute(phone))
        assertEquals(false, InputDeviceResolver.needsBluetoothRoute(wired))
    }

    // ---- #171: the recorder's colour is a projection of the same resolution ----
    // Product outcome: when a row here fails, the bubble is blue while the phone (or a wired headset)
    // records, or brand while the earbuds do.

    @Test
    fun theEarbudsAreTheMicrophoneWheneverAutoWouldOpenABluetoothRoute() {
        val auto = InputDevicePick.Auto
        assertEquals("LE Audio", true, InputDeviceResolver.earbudsAreTheMicrophone(auto, listOf(phone, buds, telephony)))
        assertEquals("classic SCO", true, InputDeviceResolver.earbudsAreTheMicrophone(auto, listOf(phone, airpods)))
        assertEquals("a wired headset beats the earbuds", false, InputDeviceResolver.earbudsAreTheMicrophone(auto, listOf(phone, airpods, wired)))
        assertEquals("USB only", false, InputDeviceResolver.earbudsAreTheMicrophone(auto, listOf(phone, usb)))
        assertEquals("phone only", false, InputDeviceResolver.earbudsAreTheMicrophone(auto, listOf(telephony, phone, submix)))
    }

    @Test
    fun anExplicitPickColoursByWhatThePickResolvesTo() {
        assertEquals("Phone picked with earbuds in", false, InputDeviceResolver.earbudsAreTheMicrophone(phone.pick, listOf(phone, airpods)))
        assertEquals("earbuds picked and present", true, InputDeviceResolver.earbudsAreTheMicrophone(airpods.pick, listOf(phone, airpods)))
        assertEquals("picked earbuds missing, other earbuds present: Auto picks them", true, InputDeviceResolver.earbudsAreTheMicrophone(airpods.pick, listOf(phone, buds)))
        assertEquals("picked earbuds missing, phone only", false, InputDeviceResolver.earbudsAreTheMicrophone(airpods.pick, listOf(phone)))
    }

    @Test
    fun noMicrophoneAtAllIsNotTheEarbuds() {
        assertEquals(false, InputDeviceResolver.earbudsAreTheMicrophone(InputDevicePick.Auto, emptyList()))
        assertEquals(false, InputDeviceResolver.earbudsAreTheMicrophone(InputDevicePick.Auto, listOf(telephony, submix)))
    }
}
