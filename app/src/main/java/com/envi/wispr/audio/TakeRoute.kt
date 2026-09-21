package com.envi.wispr.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.SystemClock
import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What `startRecording` needs to know about the chosen route, resolved under `sessionLock`. */
internal class ResolvedRoute(
    val target: InputDeviceCandidate,
    val info: AudioDeviceInfo,
    val reason: InputRouteReason,
    val needsBluetooth: Boolean,
    /** The communication sink selected for a Bluetooth target; null otherwise or when none was found. */
    val sink: AudioDeviceInfo?,
)

/** A warm hold's route on its way to the next take, with the identity the hold was keeping. */
internal class HandedRoute(val route: RouteHold, val sinkType: Int, val sinkName: String)

/**
 * The route thread's work, as one owner: post, delayed post and removal on the `AudioRouteThread`
 * handler. Injected so a JVM test can count what [TakeRoute.close] removes.
 */
internal interface RouteScheduler {
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
}

/**
 * The route of ONE take (#188): the platform route request ([hold]), what was resolved, what actually
 * captured ([effective]), the live gate, the routing listener, the sink watch and the live deadline.
 *
 * Owned by the take's `CaptureSession`; the service constructs it after the `AudioRecord` exists and
 * closes it exactly once. Session identity and the lock stay the service's: the deadline receives
 * `locked`, `stillWaiting` and `onRefused` and evaluates them where the original did. The routing
 * listener guards on [RouteHold.isReleased]; the sink and `markLive` callbacks only capture this take.
 */
internal class TakeRoute(
    val hold: RouteHold,
    val resolved: ResolvedRoute,
    val effective: EffectiveDevice,
    val gate: LiveGate,
    /** The take asked for earbuds (a Bluetooth target); the phone may then only record if picked. */
    val phonePicked: Boolean,
    private val listenerSlot: AtomicReference<Pair<AudioRecord, AudioRouting.OnRoutingChangedListener>?>,
    private val scheduler: RouteScheduler,
    private val unregisterDeviceCallback: (AudioDeviceCallback) -> Unit,
    private val tag: String,
) {
    companion object {
        /**
         * The platform route request, built BEFORE the session so every failure path can release it. Its
         * listener remover targets the slot the take will own, so the hold removes exactly this one.
         */
        fun newHold(
            audioManager: AudioManager,
            listenerSlot: AtomicReference<Pair<AudioRecord, AudioRouting.OnRoutingChangedListener>?>,
        ): RouteHold = RouteHold(
            clearCommunicationDevice = { audioManager.clearCommunicationDevice() },
            removeListener = { listenerSlot.get()?.let { (r, l) -> r.removeOnRoutingChangedListener(l) } },
        )

        /**
         * Pick the device and, for a Bluetooth target, select the headset as the communication device: the
         * one call that makes Android open the link for `VOICE_RECOGNITION` (measured 2026-09-16: the
         * preferred device alone records silence; the communication device alone starts on the phone).
         *
         * A refusal on the Bluetooth path (no sink, `false`, a throw) does NOT re-resolve onto the phone: with
         * earbuds connected the phone may only record when picked (founder rule 2026-09-18). The take keeps
         * the earbud source with `LINK_REFUSED`, and the live gate's reset and notice speak for it. Returns
         * null only when nothing at all can record.
         *
         * [handedOver] is a warm hold's route: when its sink is the one this take wants, the platform request
         * is adopted untouched and no call is made; otherwise it is released here before anything is set.
         */
        fun resolve(
            audioManager: AudioManager,
            pick: InputDevicePick,
            hold: RouteHold,
            handedOver: HandedRoute?,
            tag: String,
        ): ResolvedRoute? {
            val infos = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val candidates = infos.map(InputDeviceCandidate::from)
            val resolution = InputDeviceResolver.resolve(pick, candidates)
            val target = resolution.target ?: run { handedOver?.route?.release(); return null }
            var reason = resolution.reason
            var sinkInfo: AudioDeviceInfo? = null

            if (InputDeviceResolver.needsBluetoothRoute(target)) {
                val opened = runCatching {
                    val available = audioManager.availableCommunicationDevices
                    val sink = InputDeviceResolver.communicationSinkFor(target, available.map(InputDeviceCandidate::from))
                        ?: return@runCatching false
                    sinkInfo = available.first { it.id == sink.id }
                    val held = handedOver != null && handedOver.sinkType == sink.type && handedOver.sinkName == sink.name
                    if (held) {
                        hold.adoptCommunicationFrom(handedOver!!.route)
                        DebugLogger.log(tag, "route adopt=${target.label} from the warm hold")
                        true
                    } else {
                        handedOver?.route?.release()
                        audioManager.setCommunicationDevice(sinkInfo!!).also { if (it) hold.markCommunicationSet() }
                    }
                }.getOrElse { e ->
                    DebugLogger.warn(tag, "setCommunicationDevice threw: ${e.message}")
                    false
                }
                if (!opened) {
                    DebugLogger.warn(tag, "Bluetooth link refused for ${target.label}; staying on the earbuds")
                    handedOver?.route?.release()
                    hold.releaseCommunicationDevice()
                    reason = InputRouteReason.LINK_REFUSED
                }
            } else {
                handedOver?.route?.release()
            }
            val info = infos.firstOrNull { it.id == target.id } ?: return null
            return ResolvedRoute(
                target = target,
                info = info,
                reason = reason,
                needsBluetooth = InputDeviceResolver.needsBluetoothRoute(target),
                sink = sinkInfo,
            )
        }
    }

    /** The take asked for earbuds (a Bluetooth target); the phone may then only record if picked. */
    val targetBluetooth: Boolean get() = resolved.needsBluetooth

    /** The communication sink the take selected, for the one reset and for the hold. Null off Bluetooth. */
    val sink: AudioDeviceInfo? get() = resolved.sink

    /**
     * When the recorder started, so `live after N ms` can be logged. Taken by [markRecorderStarted]
     * right after `AudioRecord.startRecording()`, never earlier: the route request, the file and the
     * recorder's own start are not the user's wait (Codex code review 1).
     */
    @Volatile var startedAtMs: Long = 0L
        private set

    /** Set on the capture thread when the gate opens; the timer and the duration cap count from here. */
    @Volatile var liveAtMs: Long = 0L

    /**
     * The earbuds this take asked for have been removed (their sink left the device list). Set on
     * the route thread by [sinkWatch]; read on the capture thread. Once true the phone may record:
     * the earbuds are disconnected, which is the one case the founder's rule allows.
     */
    @Volatile var sinkGone: Boolean = false
    @Volatile private var sinkWatch: AudioDeviceCallback? = null

    /** The route thread's deadline message for this take, removed at every end. */
    @Volatile private var deadline: Runnable? = null
    private val closed = AtomicBoolean(false)

    /**
     * Only a Bluetooth target, or any explicit pick, names a preferred device; Auto on wired, USB or the
     * phone leaves today's behaviour untouched. A refusal is recorded and the take proceeds on whatever
     * Android routes, reported truthfully by `routedDevice`, never by the target.
     */
    /** Once, after `record.startRecording()` returned. */
    fun markRecorderStarted(atMs: Long) {
        check(startedAtMs == 0L) { "the recorder start was already marked" }
        startedAtMs = atMs
    }

    fun applyPreferred(record: AudioRecord) {
        if (!resolved.needsBluetooth && resolved.reason != InputRouteReason.PICKED) return
        val accepted = runCatching { record.setPreferredDevice(resolved.info) }.getOrDefault(false)
        if (!accepted) {
            DebugLogger.warn(tag, "setPreferredDevice refused for ${resolved.target.label}")
            effective.markReason(InputRouteReason.PREFERRED_REFUSED)
            // The link is given back; listener ownership stays with the take so its route changes are recorded.
            hold.releaseCommunicationDevice()
        }
    }

    /**
     * Registered on the route thread, removed by the hold. A callback checks the hold before writing so
     * one already running when cleanup starts writes nothing; a callback for a dead session finds its
     * own take object, never the live one. [bytesWritten] is the take's byte count for the log line.
     */
    fun registerListener(record: AudioRecord, handler: android.os.Handler, bytesWritten: () -> Long) {
        val listener = AudioRouting.OnRoutingChangedListener { router ->
            if (hold.isReleased) return@OnRoutingChangedListener
            val device = runCatching { router.routedDevice }.getOrNull() ?: return@OnRoutingChangedListener
            effective.observe(device.type, device.productName?.toString().orEmpty())
            DebugLogger.log(tag, "route change=${effective.label()} at ${bytesWritten()} bytes")
        }
        runCatching {
            record.addOnRoutingChangedListener(listener, handler)
        }.onSuccess {
            hold.markListenerSet()
            listenerSlot.set(record to listener)
        }.onFailure { DebugLogger.warn(tag, "Routing listener not registered: ${it.message}") }
    }

    /** Read the final route while the recorder is still active. Null preserves the history as it stands. */
    fun observeFinal(record: AudioRecord) {
        val device = runCatching { record.routedDevice }.getOrNull() ?: return
        effective.observe(device.type, device.productName?.toString().orEmpty())
    }

    /**
     * May a read on the OBSERVED route open the gate? An earbud target that Android is routing to the
     * phone may not, unless the phone was picked or the earbuds have left: the founder's rule, applied
     * to the observation and never to the request. A route not yet observed is not refused.
     *
     * The rule binds what this app SELECTS, and is enforced at the gate. A route Android moves by itself
     * once the take is live (V7, a call taking the link) is recorded on the History card ("AirPods Pro 3,
     * then Phone") and not fought: ending a take mid-sentence would lose the words, and capture must never
     * fail (architecture: heart and limbs). Decided at Codex code review 5, 2026-09-18.
     */
    fun admissible(): Boolean =
        !targetBluetooth || phonePicked || sinkGone || effective.currentKind != InputRouteKind.PHONE

    /**
     * Watch the take's earbuds leave, on the route thread, so the gate can admit the phone once they
     * are gone (V7: Android moves the route itself within 120 ms). Registered after the session exists,
     * removed in [close].
     */
    fun watchSink(audioManager: AudioManager, handler: android.os.Handler) {
        val sink = sink ?: return
        val type = sink.type
        val name = sink.productName?.toString().orEmpty()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.type == type && it.productName?.toString().orEmpty() == name }) {
                    sinkGone = true
                    DebugLogger.log(tag, "route earbuds removed while ${gate.state}; the phone may record")
                }
            }
        }
        sinkWatch = callback
        runCatching { audioManager.registerAudioDeviceCallback(callback, handler) }
            .onFailure { DebugLogger.warn(tag, "sink watch not registered: ${it.message}") }
        // Reconcile once: a removal between route resolution and this registration is not replayed by
        // the callback (Codex review 5). The list is read AFTER registering, so nothing can fall between.
        val stillOffered = runCatching {
            audioManager.availableCommunicationDevices.any { it.type == type && it.productName?.toString().orEmpty() == name }
        }.getOrDefault(true)
        if (!stillOffered) {
            sinkGone = true
            DebugLogger.log(tag, "route earbuds already gone at start; the phone may record")
        }
    }

    /** Capture thread, on the read that opened the gate. */
    fun markLive() {
        liveAtMs = SystemClock.elapsedRealtime()
        scheduler.post {
            deadline?.let { scheduler.removeCallbacks(it) }
            deadline = null
            DebugLogger.log(
                tag,
                "route live=${effective.label()} after ${liveAtMs - startedAtMs} ms " +
                    "resets=${gate.resetsUsed} state=${gate.state}",
            )
        }
    }

    /**
     * The live deadline runs on the route thread as a clock, so a blocked read cannot starve it. First
     * miss: reset the communication device once, if the sink is still there. Second miss: proceed without
     * sound on the earbuds (FORCED), or, when the observed route is the phone with earbuds connected,
     * fail the take rather than record from the phone.
     *
     * [locked] runs its block under the service's session lock; [stillWaiting] is the service's check
     * that this take is still the live one, recording, with the gate WAITING, evaluated inside it; [onRefused] is the
     * service's failure of the take (its `lastStartFailure` and `endTakeLocked`).
     */
    fun armDeadline(
        audioManager: AudioManager,
        locked: (() -> Unit) -> Unit,
        stillWaiting: () -> Boolean,
        onRefused: () -> Unit,
    ) {
        val runnable = object : Runnable {
            override fun run() {
                locked {
                    if (!stillWaiting()) return@locked
                    when (gate.deadlinePassed()) {
                        LiveGate.DeadlineAction.NONE -> return@locked
                        LiveGate.DeadlineAction.RESET -> {
                            val reset = reset(audioManager)
                            DebugLogger.warn(tag, "route reset=${effective.label()} performed=$reset after ${LiveGate.DEADLINE_MS} ms")
                            deadline = this
                            scheduler.postDelayed(this, LiveGate.DEADLINE_MS)
                        }
                        LiveGate.DeadlineAction.FORCE -> {
                            deadline = null
                            if (admissible()) {
                                gate.force()
                                liveAtMs = SystemClock.elapsedRealtime()
                                DebugLogger.warn(tag, "route forced=${effective.label()} after ${liveAtMs - startedAtMs} ms")
                            } else {
                                DebugLogger.warn(tag, "route refused=${effective.label()}: earbuds connected, phone would record; failing the take")
                                onRefused()
                            }
                        }
                    }
                }
            }
        }
        deadline = runnable
        scheduler.postDelayed(runnable, LiveGate.DEADLINE_MS)
    }

    /** Route thread, under the session lock. Clear and re-select the sink, only while it is still offered. */
    private fun reset(audioManager: AudioManager): Boolean {
        val sink = sink ?: return false
        val stillThere = runCatching {
            audioManager.availableCommunicationDevices.any { it.type == sink.type && it.productName?.toString() == sink.productName?.toString() }
        }.getOrDefault(false)
        if (!stillThere) return false
        return runCatching {
            audioManager.clearCommunicationDevice()
            audioManager.setCommunicationDevice(sink).also { if (it) hold.markCommunicationSet() }
        }.getOrElse { e ->
            DebugLogger.warn(tag, "communication device reset threw: ${e.message}")
            false
        }
    }

    /**
     * The deadline and the sink watch go, under the session lock, first thing at release: a late
     * deadline or removal callback then finds nothing to do. Idempotent; [close] runs it again.
     */
    fun stopWatching() {
        deadline?.let { runCatching { scheduler.removeCallbacks(it) } }
        deadline = null
        sinkWatch?.let { runCatching { unregisterDeviceCallback(it) } }
        sinkWatch = null
    }

    /**
     * Everything above, then the route is released HERE, synchronously, before the session slot frees,
     * so a later start can never install a request that an earlier release still has to clear. With
     * [keepRoute] only the recorder's listener goes (it dies with the `AudioRecord`); the communication
     * ownership stays in the `RouteHold` the warm hold now carries. Idempotent.
     */
    fun close(keepRoute: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        stopWatching()
        runCatching { if (keepRoute) hold.releaseListener() else hold.release() }
    }
}
