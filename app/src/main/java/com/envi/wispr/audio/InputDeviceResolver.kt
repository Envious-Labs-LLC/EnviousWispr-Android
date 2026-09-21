package com.envi.wispr.audio

import android.media.AudioDeviceInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which microphone a take records from. Pure over [InputDeviceCandidate], so the Auto order is a unit
 * test and the phone only has to prove the calls.
 *
 * Founder decision 2026-09-15: Auto uses connected earbuds, like a phone call. The macOS order (built-in
 * first) would never use earbuds on a phone, because a phone always has a built-in microphone.
 */
internal object InputDeviceResolver {

    /** The device to record from, or null when nothing at all can record, and why it was chosen. */
    data class Resolution(val target: InputDeviceCandidate?, val reason: InputRouteReason) {
        val kind: InputRouteKind get() = target?.let { InputRouteKind.of(it.type) } ?: InputRouteKind.NONE
    }

    /** Wired and USB first: Android already routes there, and a plugged-in headset is a deliberate act. */
    private val WIRED_OR_USB = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    /**
     * LE Audio before classic SCO: a headset that offers both exposes both, and the LE path skips the
     * SCO handshake. The LE branch is from the Android 16 source, not from a measurement; the plan
     * says so (issue #26, §14).
     */
    private val BLUETOOTH = intArrayOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    /**
     * Never picked by Auto, always offered in the explicit list. A hearing aid's microphone is not what a
     * person dictating expects to speak into, and a broadcast source is not a microphone at all. A watch
     * that exposes a SCO microphone cannot be told apart by type; the explicit list is the escape.
     */
    private val EXCLUDED_FROM_AUTO = setOf(
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    /**
     * A source a person can speak into. The phone also lists the telephony port and the remote submix
     * as sources; neither is a microphone, and an explicit pick of one records the wrong thing.
     */
    fun isMicrophone(candidate: InputDeviceCandidate): Boolean =
        candidate.isSource && InputRouteKind.of(candidate.type) != InputRouteKind.NONE &&
            candidate.type != AudioDeviceInfo.TYPE_BLE_BROADCAST

    fun resolve(pick: InputDevicePick, inputs: List<InputDeviceCandidate>, allowBluetooth: Boolean = true): Resolution {
        val sources = inputs.filter(::isMicrophone)
        if (pick is InputDevicePick.Device) {
            // An explicit Bluetooth pick whose link was just refused is "missing" for this take: honouring
            // it without the link would record silence (V4, 2026-09-16).
            val exact = sources.firstOrNull { it.type == pick.type && it.name == pick.name }
                ?.takeIf { allowBluetooth || !needsBluetoothRoute(it) }
            if (exact != null) return Resolution(exact, InputRouteReason.PICKED)
            return auto(sources, allowBluetooth).let { Resolution(it, InputRouteReason.PICK_MISSING) }
        }
        return Resolution(auto(sources, allowBluetooth), InputRouteReason.AUTO)
    }

    /**
     * The Bluetooth sink Android pairs with [target] for `setCommunicationDevice`: same name AND the same
     * transport. A headset that exposes both LE Audio and classic under one name has two sinks, and
     * opening the classic link for the LE microphone (or the reverse) records silence (Codex, 2026-09-17).
     */
    fun communicationSinkFor(target: InputDeviceCandidate, sinks: List<InputDeviceCandidate>): InputDeviceCandidate? =
        sinks.firstOrNull { it.isSink && it.name == target.name && it.type == target.type }

    /** True when routing this target needs the two Bluetooth calls; wired, USB and built-in need none. */
    fun needsBluetoothRoute(target: InputDeviceCandidate): Boolean =
        InputRouteKind.of(target.type) == InputRouteKind.BLUETOOTH

    /**
     * Whether a take started now would record through the earbuds: [resolve] for [pick] over [inputs],
     * then [needsBluetoothRoute] on what it chose. False for the phone, a wired or USB headset, and for
     * no microphone at all. The recorder colours its lips and rail on this (#171); it is a projection
     * of the same resolution the capture service makes, never a second decision.
     */
    fun earbudsAreTheMicrophone(pick: InputDevicePick, inputs: List<InputDeviceCandidate>): Boolean =
        resolve(pick, inputs).target?.let(::needsBluetoothRoute) ?: false

    /** The built-in microphone, for the rescue and the fallback. Null only on a phone with no microphone. */
    fun builtIn(inputs: List<InputDeviceCandidate>): InputDeviceCandidate? =
        inputs.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** The picker's rows: one per microphone identity, never a port a person cannot speak into. */
    fun pickable(inputs: List<InputDeviceCandidate>): List<InputDeviceCandidate> =
        inputs.filter(::isMicrophone).distinctBy { it.type to it.name }

    private fun auto(sources: List<InputDeviceCandidate>, allowBluetooth: Boolean): InputDeviceCandidate? {
        val eligible = sources.filter { it.type !in EXCLUDED_FROM_AUTO }
        firstOfTypes(eligible, WIRED_OR_USB)?.let { return it }
        if (allowBluetooth) firstOfTypes(eligible, BLUETOOTH)?.let { return it }
        eligible.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { return it }
        return null
    }

    private fun firstOfTypes(sources: List<InputDeviceCandidate>, types: IntArray): InputDeviceCandidate? {
        for (type in types) sources.firstOrNull { it.type == type }?.let { return it }
        return null
    }
}

/**
 * One take's ownership of its route. Created BEFORE the session, released on every path out of
 * `startRecording` and once more in `closeResources`, so a routing request never outlives the attempt
 * that made it, session or no session.
 *
 * [release] is idempotent and undoes exactly what was set: a hold that never set a communication device
 * never clears one, so it cannot clear a request that belongs to somebody else.
 */
internal class RouteHold(
    private val clearCommunicationDevice: () -> Unit,
    private val removeListener: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val communicationSet = AtomicBoolean(false)
    @Volatile private var listenerSet = false

    fun markCommunicationSet() { communicationSet.set(true) }
    fun markListenerSet() { listenerSet = true }
    val isReleased: Boolean get() = released.get()

    /**
     * Give back the communication request alone, keeping listener ownership: a refused link mid-setup
     * means the take continues on another device, and its route changes must still be recorded.
     */
    fun releaseCommunicationDevice() {
        if (communicationSet.compareAndSet(true, false)) runCatching { clearCommunicationDevice() }
    }

    /**
     * Drop the recorder's routing listener alone. The listener belongs to that `AudioRecord` and dies
     * with it at every close; the communication ownership may outlive it in a `WarmHold`.
     */
    fun releaseListener() {
        if (listenerSet) {
            listenerSet = false
            runCatching { removeListener() }
        }
    }

    /**
     * Take the communication ownership from a hold that is handing over to this take: the platform
     * request stays exactly as it is, [other] forgets it (so its later release clears nothing), and this
     * hold now owns the one clear.
     */
    fun adoptCommunicationFrom(other: RouteHold) {
        if (other.communicationSet.compareAndSet(true, false)) communicationSet.set(true)
    }

    /** Everything, once. The take is over. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        releaseListener()
        releaseCommunicationDevice()
    }
}
