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
object InputDeviceResolver {

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

    fun resolve(pick: InputDevicePick, inputs: List<InputDeviceCandidate>, allowBluetooth: Boolean = true): Resolution {
        val sources = inputs.filter { it.isSource }
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

    /** The built-in microphone, for the rescue and the fallback. Null only on a phone with no microphone. */
    fun builtIn(inputs: List<InputDeviceCandidate>): InputDeviceCandidate? =
        inputs.firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

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
class RouteHold(
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

    /** Everything, once. The take is over. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        if (listenerSet) runCatching { removeListener() }
        releaseCommunicationDevice()
    }
}

/**
 * The silent-earbud rescue's counter: pure over bytes read, so the bar is a unit test.
 *
 * Armed only for a Bluetooth target. Counts exact-zero bytes from the first byte of the take; retires
 * for good at the first non-zero sample (a healthy link cannot re-arm it) and fires at most once. The
 * bar is audio delivered, not wall-clock: a blocked read makes no progress and no decision.
 *
 * 3.0 s is the macOS ceiling (50 % headroom over the founder's observed 2 s worst case); the Android
 * cold link-up measured 0.56 to 0.96 s, so the bar sits about 3x past the measured tail.
 */
class SilentRouteRescue(private val armed: Boolean) {
    private var zeroBytes = 0L
    private var retired = !armed
    private var fired = false

    /** True exactly once, on the read that crosses the bar with no non-zero sample seen so far. */
    fun offer(buffer: ByteArray, bytesRead: Int): Boolean {
        if (retired || fired) return false
        for (i in 0 until bytesRead) {
            if (buffer[i].toInt() != 0) {
                retired = true
                return false
            }
        }
        zeroBytes += bytesRead
        if (zeroBytes >= RESCUE_AFTER_BYTES) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        /** 3.0 s at 16 kHz, 16-bit mono. */
        const val RESCUE_AFTER_BYTES: Long = 3L * PcmAudio.SAMPLE_RATE * PcmAudio.BYTES_PER_SAMPLE
    }
}
