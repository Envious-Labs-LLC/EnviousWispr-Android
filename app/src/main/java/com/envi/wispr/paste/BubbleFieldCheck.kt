package com.envi.wispr.paste

/**
 * One request to re-check which editor the bubble should offer (#561). The check itself is accessibility
 * IPC into other processes (the window list, the active root, `findFocus`, `refresh`), so it runs off the
 * main thread in [BubbleFieldChecks], which keeps one running and at most one waiting. A burst of events
 * merges into the waiting check with [merge]; running the merged check once reads the screen as it is
 * then, which is what running each of them in turn would have ended on.
 *
 * Pure and free of Android types, so the merge and the decisions below are tested on the JVM.
 */
internal data class BubbleFieldCheck(
    /** Re-check the remembered editor without a traversal (a click, a preference change, a withdrawn field). */
    val revalidate: Boolean = false,
    /** A transition that warrants discovery and a retry sequence: a connect, a window state change, an unremembered focus. */
    val transition: Boolean = false,
    /** The uptime of the newest direct [transition] merged in, from which a fresh retry sequence measures its offsets. */
    val transitionUptime: Long = 0L,
    /** The retry-sequence generation this is an attempt of, or null. */
    val retryOf: Int? = null,
    /**
     * Windows-changed events by window id: the uptime of the event when it warranted discovery, null when it
     * was cosmetic. An event whose window turns out to be an accessibility overlay (our own bubble moving)
     * asks for nothing, its uptime included; which windows those are is itself IPC, so [resolve] answers it
     * off main.
     */
    val windowEvents: Map<Int, Long?> = emptyMap(),
) {
    val isEmpty: Boolean get() = !revalidate && !transition && retryOf == null && windowEvents.isEmpty()

    /** This check and a [newer] one as one check that asks for everything either asked for. */
    fun merge(newer: BubbleFieldCheck): BubbleFieldCheck = BubbleFieldCheck(
        revalidate = revalidate || newer.revalidate,
        transition = transition || newer.transition,
        transitionUptime = maxOf(transitionUptime, newer.transitionUptime),
        retryOf = newer.retryOf ?: retryOf,
        windowEvents = (windowEvents.keys + newer.windowEvents.keys).associateWith { id ->
            listOfNotNull(windowEvents[id], newer.windowEvents[id]).maxOrNull()
        },
    )

    /**
     * The same check once its retry sequence was cancelled (a field found the direct way, a fresh lock
     * sequence, a teardown): it loses its discovery and its retries, never the re-check of what is on screen.
     */
    fun withoutSequence(): BubbleFieldCheck = BubbleFieldCheck(
        revalidate = revalidate || transition || retryOf != null,
        windowEvents = windowEvents.mapValues { null },
    )

    /** The same check without an attempt of a sequence other than [generation] (one a restart superseded). */
    fun withoutRetryOtherThan(generation: Int): BubbleFieldCheck =
        if (retryOf != null && retryOf != generation) copy(retryOf = null) else this

    /**
     * What to ask again when this check's answer could not be used (the screen changed while it ran):
     * everything it asked, unless the retry sequence was cancelled since it began ([startedIn] is no longer
     * [generation]), in which case only its re-check, as [withoutSequence] strips the waiting check.
     */
    fun askAgain(startedIn: Int, generation: Int): BubbleFieldCheck =
        if (startedIn == generation) this else withoutSequence()

    /**
     * What this check asks once the accessibility overlay windows are known, or null when every event in it
     * was an overlay's own (re-checking then would let our own bubble's moves drive traversals). The overlay
     * events are dropped FIRST, so an overlay's warranted change cannot move a real transition's uptime.
     */
    fun resolve(overlayWindowIds: Set<Int>): Resolved? {
        val live = windowEvents.filterKeys { it !in overlayWindowIds }
        if (!revalidate && !transition && retryOf == null && live.isEmpty()) return null
        val warrantedUptimes = live.values.filterNotNull()
        val uptimes = if (transition) warrantedUptimes + transitionUptime else warrantedUptimes
        return Resolved(
            startsSequence = uptimes.isNotEmpty(),
            retryOf = retryOf,
            transitionUptime = uptimes.maxOrNull() ?: 0L,
        )
    }

    /** A check with its overlay-only events removed. */
    data class Resolved(val startsSequence: Boolean, val retryOf: Int?, val transitionUptime: Long = 0L) {
        /**
         * Whether to traverse for the focused editor. A retry attempt always does. A transition does unless
         * a sequence is already chasing this same focused window ([chasingWindowId], -1 when the focused
         * window was unknown, null when no sequence runs): that sequence's own attempts are the traversals,
         * so a burst of events for one return does not multiply them.
         */
        fun discovers(focusedWindowId: Int, chasingWindowId: Int?): Boolean =
            retryOf != null || (startsSequence && focusedWindowId != chasingWindowId)
    }
}

/** What the retry sequence does once a check has answered. Owner: [discoveryStep]. */
internal sealed interface DiscoveryStep {
    /** The field is here: end any sequence. */
    data object Stop : DiscoveryStep

    /** A transition into a window no sequence chases: start a fresh one for it. */
    data class Restart(val windowId: Int) : DiscoveryStep

    /** This sequence's attempt found nothing: schedule its next one. */
    data object Continue : DiscoveryStep

    /** Leave the sequence as it is. */
    data object Keep : DiscoveryStep
}

/**
 * The retry sequence's decision after a check answered on main. [startedIn] is the sequence generation when
 * the check began and [generation] the one now: a cancel in between (a field found the direct way, a fresh
 * lock sequence, a teardown) means this answer may not start or continue a sequence, so a torn-down service
 * never schedules a retry from an answer that was already in flight. A check that answered nothing (its
 * blocking half threw) is asked with `fieldPresent = false` and no transition, so a running sequence still
 * moves on to its next attempt or ends rather than stalling with nothing scheduled.
 */
internal fun discoveryStep(
    fieldPresent: Boolean,
    resolved: BubbleFieldCheck.Resolved,
    focusedWindowId: Int,
    chasingWindowId: Int?,
    startedIn: Int,
    generation: Int,
): DiscoveryStep = when {
    fieldPresent -> DiscoveryStep.Stop
    startedIn != generation -> DiscoveryStep.Keep
    resolved.startsSequence && focusedWindowId != chasingWindowId -> DiscoveryStep.Restart(focusedWindowId)
    resolved.retryOf == generation -> DiscoveryStep.Continue
    else -> DiscoveryStep.Keep
}
