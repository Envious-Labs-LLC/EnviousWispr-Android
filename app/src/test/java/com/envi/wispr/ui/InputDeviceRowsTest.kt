package com.envi.wispr.ui

import android.media.AudioDeviceInfo
import com.envi.wispr.audio.InputDeviceCandidate
import com.envi.wispr.audio.InputDevicePick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product outcome: when a row here fails, the Settings page selects the wrong microphone row, lists a
 * device that is not there, or names the wrong microphone under Auto. The rule is the Mac's (#173):
 * a pick that is not connected is remembered, not shown; Auto names what it would open.
 */
class InputDeviceRowsTest {

    private var nextId = 100
    private fun device(type: Int, name: String) =
        InputDeviceCandidate(id = nextId++, type = type, name = name, isSource = true, isSink = false)

    private val phone = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "SM-S948U1")
    private val airpods = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AirPods Pro 3")
    private val storm = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Storm")
    private val stormLe = device(AudioDeviceInfo.TYPE_BLE_HEADSET, "Storm")
    private val wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")
    private val hearingAid = device(AudioDeviceInfo.TYPE_HEARING_AID, "Aid")
    private val nameless = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "")
    private val telephony = device(AudioDeviceInfo.TYPE_TELEPHONY, "SM-S948U1")

    private fun selectedTitles(rows: List<InputDeviceRows.Row>) = rows.filter { it.selected }.map { it.title }

    @Test
    fun autoWithThePhoneAloneSaysUsingPhone() {
        val rows = InputDeviceRows.build(InputDevicePick.Auto, listOf(phone, telephony))
        assertEquals(listOf("Auto", "Phone"), rows.map { it.title })
        assertEquals("Using Phone", rows[0].subtitle)
        assertEquals(listOf("Auto"), selectedTitles(rows))
    }

    @Test
    fun autoWithEarbudsNamesTheEarbudsAndKeepsThemUnselected() {
        val rows = InputDeviceRows.build(InputDevicePick.Auto, listOf(phone, airpods))
        assertEquals("Using AirPods Pro 3", rows[0].subtitle)
        assertEquals(listOf("Auto"), selectedTitles(rows))
        assertEquals(listOf("Auto", "Phone", "AirPods Pro 3"), rows.map { it.title })
    }

    @Test
    fun autoFollowsTheAutoOrderWiredBeforeEarbuds() {
        val rows = InputDeviceRows.build(InputDevicePick.Auto, listOf(phone, airpods, wired))
        assertEquals("Using Headset", rows[0].subtitle)
    }

    @Test
    fun aHearingAidIsOfferedButNeverChosenByAuto() {
        val rows = InputDeviceRows.build(InputDevicePick.Auto, listOf(phone, hearingAid))
        assertEquals("Using Phone", rows[0].subtitle)
        assertEquals(listOf("Auto", "Phone", "Aid"), rows.map { it.title })
    }

    @Test
    fun aConnectedPickIsSelectedAndAutoShowsTheExplainer() {
        val rows = InputDeviceRows.build(airpods.pick, listOf(phone, airpods))
        assertEquals(listOf("AirPods Pro 3"), selectedTitles(rows))
        assertEquals(InputDeviceRows.AUTO_EXPLAINER, rows[0].subtitle)
        assertNull(rows[2].subtitle)
    }

    @Test
    fun anAbsentPickIsRememberedNotShownAndAutoNamesTheOtherEarbuds() {
        // The founder's 2026-09-18 case: AirPods picked and off, the Bose "Storm" connected.
        val rows = InputDeviceRows.build(airpods.pick, listOf(phone, storm))
        assertEquals(listOf("Auto", "Phone", "Storm"), rows.map { it.title })
        assertEquals("Using Storm", rows[0].subtitle)
        assertEquals(listOf("Auto"), selectedTitles(rows))
        // The stored pick is the caller's; the rows never rewrite it. When the AirPods return, the same
        // pick selects them again.
        assertEquals(listOf("AirPods Pro 3"), selectedTitles(InputDeviceRows.build(airpods.pick, listOf(phone, storm, airpods))))
    }

    @Test
    fun anAbsentPickWithNothingConnectedSaysNoMicrophoneFound() {
        val rows = InputDeviceRows.build(airpods.pick, emptyList())
        assertEquals(listOf("Auto"), rows.map { it.title })
        assertEquals(InputDeviceRows.NO_MICROPHONE, rows[0].subtitle)
        assertEquals(listOf("Auto"), selectedTitles(rows))
    }

    @Test
    fun theSameNameOnTwoTransportsIsTwoRowsAndThePickedTypeIsTheSelectedOne() {
        val rows = InputDeviceRows.build(storm.pick, listOf(phone, stormLe, storm))
        assertEquals(listOf("Auto", "Phone", "Storm", "Storm"), rows.map { it.title })
        val selected = rows.filter { it.selected }
        assertEquals(1, selected.size)
        assertEquals(storm.pick, selected.single().pick)
    }

    @Test
    fun aBlankNameGetsTheKindWord() {
        val rows = InputDeviceRows.build(InputDevicePick.Auto, listOf(phone, nameless))
        assertEquals("Bluetooth microphone", rows[2].title)
        assertEquals("Using Bluetooth microphone", rows[0].subtitle)
    }

    @Test
    fun exactlyOneRowIsSelectedInEveryCase() {
        val cases = listOf(
            InputDevicePick.Auto to listOf(phone),
            InputDevicePick.Auto to listOf(phone, airpods, wired),
            airpods.pick to listOf(phone, airpods),
            airpods.pick to listOf(phone, storm),
            airpods.pick to emptyList(),
            storm.pick to listOf(phone, stormLe, storm),
            InputDevicePick.Auto to emptyList(),
        )
        cases.forEach { (pick, inputs) ->
            assertEquals("$pick over ${inputs.map { it.name }}", 1, InputDeviceRows.build(pick, inputs).count { it.selected })
        }
    }
}
