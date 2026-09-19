package com.envi.wispr.ui

import com.envi.wispr.audio.InputDeviceCandidate
import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.audio.InputDeviceResolver
import com.envi.wispr.audio.InputRouteReason

/**
 * The rows of the Input Device picker, decided from the same [InputDeviceResolver.resolve] the capture
 * service runs when a take starts, over the list the page read. Pure, so the macOS rule is a unit test.
 *
 * The macOS rule (#173, `InputDevicePreferencePolicy` on the Mac, founder 2026-09-18): a pick that is
 * not connected is remembered, not shown. Auto shows selected and names the microphone it would open;
 * the absent device is not listed; when it reconnects its row is back and selected. Tapping Auto or any
 * listed device replaces the memory. Android keeps ONE stored pick where the Mac keeps two, because the
 * resolver already falls back per take; the page only has to show that fallback.
 */
object InputDeviceRows {
    /** What Auto does, on the row, when a device is picked and connected. */
    const val AUTO_EXPLAINER = "Earbuds when they are connected, otherwise the phone"

    /** Auto is selected and the phone lists no microphone at all: the take would fail with the same words. */
    const val NO_MICROPHONE = "No microphone found"

    data class Row(val pick: InputDevicePick, val title: String, val subtitle: String?, val selected: Boolean)

    fun build(pick: InputDevicePick, inputs: List<InputDeviceCandidate>): List<Row> {
        val resolution = InputDeviceResolver.resolve(pick, inputs)
        val autoSelected = resolution.reason != InputRouteReason.PICKED
        val autoSubtitle = when {
            !autoSelected -> AUTO_EXPLAINER
            resolution.target != null -> "Using ${resolution.target.label}"
            else -> NO_MICROPHONE
        }
        val auto = Row(InputDevicePick.Auto, "Auto", autoSubtitle, autoSelected)
        val devices = InputDeviceResolver.pickable(inputs).map { device ->
            Row(device.pick, device.label, null, !autoSelected && pick == device.pick)
        }
        return listOf(auto) + devices
    }
}
