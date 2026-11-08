package com.envi.wispr.paste

import com.envi.wispr.paste.BubbleVisibility.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRODUCT OUTCOME (#523). When this fails, the user sees the floating lips on the lock screen, on a password
 * box, or in an app they hid; or the lips vanish from an app they never hid. Each row reaches one branch of
 * [BubbleVisibility.decide] on an ordinary input; the ordering rows pin which reason wins when two apply.
 *
 * REVERT: make `decide` return `Decision.Show` always, and every hidden row turns red.
 */
class BubbleVisibilityTest {

    private val gmail = "com.google.android.gm"
    private val slack = "com.Slack"

    private fun decide(
        locked: Boolean = false,
        password: Boolean = false,
        pkg: String? = gmail,
        hidden: Set<String> = emptySet(),
        showOnPasswords: Boolean = false,
    ) = BubbleVisibility.decide(locked, password, pkg, hidden, showOnPasswords)

    @Test fun anOrdinaryFieldInAnAppNobodyHidShowsTheLips() = assertEquals(Decision.Show, decide())

    @Test fun aLockedPhoneHidesTheLips() = assertEquals(Decision.HiddenLocked, decide(locked = true))

    @Test fun anAppTheUserHidHidesTheLips() = assertEquals(Decision.HiddenApp, decide(hidden = setOf(gmail)))

    @Test fun hidingOneAppLeavesEveryOtherAppShowingTheLips() =
        assertEquals(Decision.Show, decide(pkg = gmail, hidden = setOf(slack)))

    @Test fun aPasswordBoxHidesTheLipsByDefault() = assertEquals(Decision.HiddenPassword, decide(password = true))

    @Test fun aPasswordBoxShowsTheLipsWhenTheUserAskedForThem() =
        assertEquals(Decision.Show, decide(password = true, showOnPasswords = true))

    @Test fun anUnknownAppIsNeverTreatedAsHidden() =
        assertEquals(Decision.Show, decide(pkg = null, hidden = setOf(gmail)))

    @Test fun theLockScreenWinsOverEveryOtherReason() =
        assertEquals(Decision.HiddenLocked, decide(locked = true, password = true, hidden = setOf(gmail), showOnPasswords = true))

    @Test fun aHiddenAppWinsOverThePasswordSwitch() =
        assertEquals(Decision.HiddenApp, decide(password = true, hidden = setOf(gmail), showOnPasswords = true))

    @Test fun theLogReasonNamesTheDecisionNeverThePackage() {
        assertEquals("SHOW", BubbleVisibility.reason(Decision.Show))
        assertEquals("LOCKED", BubbleVisibility.reason(Decision.HiddenLocked))
        assertEquals("APP", BubbleVisibility.reason(Decision.HiddenApp))
        assertEquals("PASSWORD", BubbleVisibility.reason(Decision.HiddenPassword))
    }
}
