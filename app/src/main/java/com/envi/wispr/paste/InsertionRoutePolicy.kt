package com.envi.wispr.paste

/** The one write a dictation makes (#141). */
internal enum class InsertionRoute {
    /** `commitText` through the accessibility input connection: the editor places the words. */
    COMMIT,

    /** The editor's own `ACTION_PASTE` on the pinned node, from a clipboard we staged. */
    PASTE,
}

/**
 * Which ONE route runs. There is no order and no fallback between routes: the choice is made before
 * any write, from whether the input session provably belongs to the pinned field, and nothing that
 * happens afterwards can select the other one (#141, review round 4: a second route was the third
 * generation of "a write dispatched but treated as unavailable", and the class was deleted).
 */
internal object InsertionRoutePolicy {
    fun select(commitEligible: Boolean): InsertionRoute =
        if (commitEligible) InsertionRoute.COMMIT else InsertionRoute.PASTE
}
