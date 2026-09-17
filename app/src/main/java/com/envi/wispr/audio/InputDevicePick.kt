package com.envi.wispr.audio

import android.media.AudioDeviceInfo

/**
 * Which microphone the user asked for. Crosses the binder and DataStore as one string.
 *
 * Identity is TYPE plus PRODUCT NAME, never `AudioDeviceInfo.getId()` (a different number for the same
 * earbuds on two runs the same day, measured 2026-09-16) and never the address (redacted to its last two
 * bytes without `BLUETOOTH_CONNECT`, which this app does not hold and does not need).
 */
sealed class InputDevicePick {
    object Auto : InputDevicePick()
    data class Device(val type: Int, val name: String) : InputDevicePick()

    fun serialize(): String = when (this) {
        Auto -> AUTO
        is Device -> "$type$SEPARATOR$name"
    }

    companion object {
        const val AUTO = "auto"
        private const val SEPARATOR = '|'

        /**
         * Garbage reads as [Auto], never as a refusal: the take is the heart, the pick is a limb. A
         * caller we do not control sending nonsense gets today's behaviour, not a failed dictation.
         */
        fun parse(raw: String?): InputDevicePick {
            if (raw.isNullOrBlank() || raw == AUTO) return Auto
            val cut = raw.indexOf(SEPARATOR)
            if (cut <= 0 || cut == raw.length - 1) return Auto
            val type = raw.substring(0, cut).toIntOrNull() ?: return Auto
            return Device(type, raw.substring(cut + 1))
        }
    }
}

/**
 * One input as the resolver sees it: a plain value, so the Auto order is tested without a phone.
 * `id` is carried only so the service can find the same `AudioDeviceInfo` in the list it read.
 */
data class InputDeviceCandidate(
    val id: Int,
    val type: Int,
    val name: String,
    val isSource: Boolean,
    val isSink: Boolean,
) {
    val pick: InputDevicePick.Device get() = InputDevicePick.Device(type, name)

    /** The words on the settings row and the History card. */
    val label: String get() = InputDeviceLabels.labelFor(type, name)

    companion object {
        fun from(info: AudioDeviceInfo): InputDeviceCandidate = InputDeviceCandidate(
            id = info.id,
            type = info.type,
            name = info.productName?.toString().orEmpty(),
            isSource = info.isSource,
            isSink = info.isSink,
        )
    }
}

/** The one place a device type becomes words a user reads. */
object InputDeviceLabels {
    const val PHONE = "Phone"

    /** The card's fallback marker: "AirPods Pro 3, then Phone". */
    const val THEN = ", then "

    fun labelFor(type: Int, name: String): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> PHONE
        else -> name.ifBlank { kindWord(type) }
    }

    private fun kindWord(type: Int): String = when (InputRouteKind.of(type)) {
        InputRouteKind.BLUETOOTH -> "Bluetooth microphone"
        InputRouteKind.WIRED_OR_USB -> "Wired microphone"
        InputRouteKind.PHONE -> PHONE
        InputRouteKind.NONE -> "Microphone"
    }
}

/**
 * The kind of device a take started on, as the app process reads it over the binder. It decides the
 * Bluetooth tip; the app never infers the route from its own device list or from the label.
 */
enum class InputRouteKind(val code: Int) {
    NONE(0),
    PHONE(1),
    WIRED_OR_USB(2),
    BLUETOOTH(3);

    companion object {
        fun of(type: Int): InputRouteKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> PHONE
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> WIRED_OR_USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> BLUETOOTH
            else -> NONE
        }

        fun fromCode(code: Int): InputRouteKind = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * Why the take is on the device it is on. The latest event wins; the app process reads the code over
 * the binder and never parses the label for it.
 */
enum class InputRouteReason(val code: Int) {
    AUTO(0),
    PICKED(1),
    PICK_MISSING(2),
    LINK_REFUSED(3),
    PREFERRED_REFUSED(4),
    RESCUED(5);

    companion object {
        fun fromCode(code: Int): InputRouteReason = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * What ACTUALLY captured one take, in order. This is the record the macOS bug lacked: the absence of a
 * per-take device record is what made "the earbuds were never used" take a day to find.
 *
 * Every write and read goes through [synchronized] on this object, and no platform call is ever made
 * while it is held: the lock guards the record, the calls happen outside it.
 */
class EffectiveDevice(startKind: InputRouteKind, startReason: InputRouteReason) {
    private val history = ArrayList<String>(3)
    private var reason: InputRouteReason = startReason
    private var rescued = false
    val kind: InputRouteKind = startKind

    /** The device the recorder reports at start, on every route change, and once more before it stops. */
    @Synchronized
    fun observe(type: Int, name: String) {
        val label = InputDeviceLabels.labelFor(type, name)
        if (history.lastOrNull() == label) return
        history.add(label)
    }

    @Synchronized
    fun markRescued() {
        rescued = true
        reason = InputRouteReason.RESCUED
    }

    /** A later event that changes why the take is where it is (a refused preferred device). */
    @Synchronized
    fun markReason(latest: InputRouteReason) {
        reason = latest
    }

    @Synchronized
    fun reasonCode(): Int = reason.code

    @Synchronized
    fun wasRescued(): Boolean = rescued

    /** "AirPods Pro 3" or "AirPods Pro 3, then Phone". Empty until the first observation. */
    @Synchronized
    fun label(): String = history.joinToString(InputDeviceLabels.THEN)
}
