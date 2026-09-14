package com.envi.wispr.ui

/**
 * The five scenes of the setup demo and their clock, as the founder approved them scene by scene on
 * 2026-09-14 from `docs/mockups/android-onboarding-demo-v1`. Pure timing: the screen asks
 * [at] which scene a moment falls in and how far into it, and every scene reads its own seconds.
 */
internal enum class DemoScene(val seconds: Float, val caption: String) {
    /** A wall of the phone's own app icons; a text box slides in and the bubble appears beside it. */
    APPS(4f, "Works in every app with a text box."),
    /** The bubble alone at real size: what it is, which edge it lives on, the three looks. */
    BUBBLE(7f, "This is your recording bubble."),
    /** A Gmail draft: tap the bubble, tap the check, the formatted email lands at once. */
    TAP(5f, "Tap the bubble to start.\nTap the check to finish."),
    /** The same draft, replied to with a hold: press, talk, let go. */
    HOLD(5f, "Or hold the bubble and talk.\nLet go to finish."),
    /** The draft fades; the real practice screen follows this card. */
    YOURS(3f, "Now it’s your turn.\nTap or hold the bubble."),
}

/** Where [elapsed] seconds of the demo fall: the scene and the seconds into it. */
internal data class DemoMoment(val scene: DemoScene, val t: Float)

internal object DemoScript {
    val total: Float = DemoScene.entries.sumOf { it.seconds.toDouble() }.toFloat()

    fun at(elapsed: Float): DemoMoment {
        var start = 0f
        for (scene in DemoScene.entries) {
            if (elapsed < start + scene.seconds || scene == DemoScene.YOURS) return DemoMoment(scene, (elapsed - start).coerceIn(0f, scene.seconds))
            start += scene.seconds
        }
        return DemoMoment(DemoScene.YOURS, DemoScene.YOURS.seconds)
    }

    /** 0 before [from], 1 after [to], smooth in between. */
    fun between(t: Float, from: Float, to: Float): Float {
        val v = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    /** The second caption line of the bubble scene, by beat; null in the other scenes. */
    fun bubbleCaption(t: Float): Pair<String, String> = when {
        t < 2.3f -> "This is your recording bubble." to "It appears whenever a text box is active."
        t < 4.6f -> "It lives on the left or the right side of your screen." to "Drag it to the side you like."
        else -> "It comes in three looks." to "Pick yours in Settings, Appearance."
    }

    /** Which of the three looks the bubble scene shows at [t]: 0 Smoke, 1 Clear, 2 Bare. */
    fun bubbleLookIndex(t: Float): Int = when {
        t < 5.4f -> 0
        t < 6.2f -> 1
        else -> 2
    }

    /** The email the tap scene lands, formatted the way polish formats an email. */
    const val TAP_EMAIL = "Hi Priya,\n\nCan we move our call to Thursday at 3 pm?\n\nThanks so much!"
    const val HOLD_EMAIL = "Sounds good.\n\nI will send the deck over tonight.\n\nTalk soon!"
    /** When the words land, seconds into their scene: after the pill has folded back into the bubble. */
    const val TAP_LANDS = 3.4f
    const val HOLD_LANDS = 3.3f
}
