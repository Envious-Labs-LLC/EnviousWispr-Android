package com.envi.wispr.paste

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.BubbleFieldsActivity
import com.envi.wispr.settings.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID

/**
 * PRODUCT OUTCOME on a real phone (#523). When this fails, the user sees the floating lips on a password box,
 * in an app they hid, or over a locked phone; or the lips do not come back where they belong.
 *
 * Every hidden check is a TRANSITION from a shown state on the same field, so the bubble's absence is never
 * read where it was never present (the positive control is the first assertion of each test). Shown means the
 * overlay's window, titled [RecordingAccessibilityOverlay.WINDOW_TITLE], is in the window manager; the overlay
 * removes that window whenever the idle bubble is not drawn.
 *
 * Staging owned here, restored in [restore]: the paste service is switched on through secure settings, the
 * bubble preferences are snapshotted and put back, and any temporary PIN is cleared. Meant for disposable
 * phones (Firebase Test Lab, the emulator); never run it on a phone with a real screen lock.
 */
@RunWith(AndroidJUnit4::class)
class BubbleVisibilityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val prefs = AppPreferences(context)
    private var savedHidden: Set<String> = emptySet()
    private var savedShowOnPasswords = false
    private var pinSet = false
    private var lockWasDisabled: String? = null
    private var savedA11yServices = ""
    private var savedA11yEnabled = ""
    private var a11ySnapshotTaken = false

    @Before
    fun stage() {
        val saved = runBlocking { prefs.bubbleVisibilityStored.first() }
        savedHidden = saved.hiddenApps
        savedShowOnPasswords = saved.showOnPasswords
        runBlocking {
            saved.hiddenApps.forEach { prefs.setBubbleHiddenInApp(it, hidden = false) }
            prefs.setBubbleShowOnPasswords(false)
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        // Snapshot BEFORE the first mutation, absent keys included ("null"), so restore() puts back what was here.
        savedA11yServices = shell("settings get secure enabled_accessibility_services").trim()
        savedA11yEnabled = shell("settings get secure accessibility_enabled").trim()
        a11ySnapshotTaken = true
        // Re-putting the same setting string does NOT rebind a service the instrumentation restart killed
        // (`android-tooling.md` RULE: install-then-force-stop): clear it, then set it.
        // executeShellCommand has no shell, so quotes would be literal: delete instead of putting "".
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
        Thread.sleep(1_500)
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        val bound = runBlocking { withTimeoutOrNull(30_000) { PasteAccessibilityService.isBound.first { it } } }
        assertTrue("staging: the paste service never bound, so no bubble can be judged", bound == true)
    }

    /**
     * Every restoration runs, whatever an earlier one did, and a failed one fails the test by name. A killed
     * process skips `@After` entirely, so a PIN can only be promised cleared on a run that reaches here.
     */
    @After
    fun restore() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try { block() } catch (error: Throwable) { failures += "$name: ${error.message}" }
        }
        step("temporary PIN") {
            if (pinSet) {
                val cleared = shell("locksettings clear --old $PIN")
                check("cleared" in cleared.lowercase()) { "locksettings clear said: ${cleared.trim()}" }
                pinSet = false
            }
        }
        step("lock screen disabled state") {
            lockWasDisabled?.let {
                if (it == "true" || it == "false") {
                    shell("locksettings set-disabled $it")
                    val now = shell("locksettings get-disabled").trim()
                    check(now == it) { "lock screen disabled reads '$now', expected '$it'" }
                }
            }
            lockWasDisabled = null
        }
        step("accessibility settings") {
            if (a11ySnapshotTaken) {
                restoreSecure("enabled_accessibility_services", savedA11yServices)
                restoreSecure("accessibility_enabled", savedA11yEnabled)
                a11ySnapshotTaken = false
            }
        }
        step("bubble preferences") {
            runBlocking {
                prefs.bubbleVisibilityStored.first().hiddenApps.forEach { prefs.setBubbleHiddenInApp(it, hidden = false) }
                savedHidden.forEach { prefs.setBubbleHiddenInApp(it, hidden = true) }
                prefs.setBubbleShowOnPasswords(savedShowOnPasswords)
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("cleanup failed: ${failures.joinToString("; ")}")
    }

    /** `settings get` prints "null" for an absent key; an absent key is deleted, never invented. */
    private fun restoreSecure(key: String, value: String) {
        if (value == "null" || value.isEmpty()) shell("settings delete secure $key") else shell("settings put secure $key $value")
        val expected = if (value.isEmpty()) "null" else value
        val now = shell("settings get secure $key").trim()
        check(now == expected) { "$key reads '$now', expected '$expected'" }
    }

    @Test
    fun aPasswordBoxHidesTheLipsAndTheSwitchBringsThemBack() {
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = true, "ordinary field (positive control)")
        focus(BubbleFieldsActivity.FOCUS_SECRET)
        awaitBubble(shown = false, "password box, default")
        runBlocking { prefs.setBubbleShowOnPasswords(true) }
        awaitBubble(shown = true, "password box, Show on password boxes ON, no focus change")
        runBlocking { prefs.setBubbleShowOnPasswords(false) }
        awaitBubble(shown = false, "password box, switch back OFF, no focus change")
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = true, "back on the ordinary field")
    }

    @Test
    fun aHiddenAppHidesTheLipsAndShowAgainBringsThemBack() {
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = true, "ordinary field (positive control)")
        runBlocking { prefs.setBubbleHiddenInApp(TEST_PACKAGE, hidden = true) }
        awaitBubble(shown = false, "app hidden while its field stays focused")
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = false, "hidden app, field focused again")
        runBlocking { prefs.setBubbleHiddenInApp(TEST_PACKAGE, hidden = false) }
        awaitBubble(shown = true, "Show again, no focus change")
    }

    @Test
    fun aLockedPhoneHidesTheLipsAndUnlockingBringsThemBack() {
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = true, "ordinary field (positive control)")
        // A phone whose lock screen was switched off never shows the keyguard; snapshot before any change.
        lockWasDisabled = shell("locksettings get-disabled").trim()
        val set = shell("locksettings set-pin $PIN")
        assertTrue("staging: could not set a temporary PIN on this phone: $set", "set" in set.lowercase())
        pinSet = true
        shell("locksettings set-disabled false")
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("staging: the phone did not lock after sleep", awaitLocked(locked = true))
        shell("input keyevent KEYCODE_WAKEUP")
        // The rig declares showWhenLocked, so its focused field sits ABOVE the secure lock.
        focus(BubbleFieldsActivity.FOCUS_NAME)
        awaitBubble(shown = false, "focused field shown over a locked phone")
        val cleared = shell("locksettings clear --old $PIN")
        assertTrue("staging: could not clear the temporary PIN: $cleared", "cleared" in cleared.lowercase())
        pinSet = false
        assertTrue("staging: the phone did not unlock", unlock())
        awaitBubble(shown = true, "unlocked, same field, no re-tap")
    }

    private fun focus(which: String) {
        val token = UUID.randomUUID().toString()
        context.startActivity(
            Intent()
                .setClassName(TEST_PACKAGE, "com.envi.wispr.BubbleFieldsActivity")
                .putExtra(BubbleFieldsActivity.EXTRA_FOCUS, which)
                .putExtra(BubbleFieldsActivity.EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("run-as $TEST_PACKAGE cat files/${BubbleFieldsActivity.READY_NAME}").trim() == token) return
            Thread.sleep(200)
        }
        throw AssertionError("staging: the $which field never reported focus")
    }

    /** Polls for the EXPECTED state, never for an escape from the other one; fails naming what it last saw. */
    private fun awaitBubble(shown: Boolean, case: String) {
        val deadline = System.currentTimeMillis() + 8_000
        var last = !shown
        while (System.currentTimeMillis() < deadline) {
            last = bubbleWindowPresent()
            if (last == shown) {
                // Held, not merely passed through: the decision must still stand a moment later.
                Thread.sleep(1_500)
                last = bubbleWindowPresent()
                if (last == shown) return
            }
            Thread.sleep(250)
        }
        assertEquals("$case: lips ${if (shown) "should show" else "should be hidden"}", shown, last)
    }

    private fun bubbleWindowPresent(): Boolean =
        shell("dumpsys window windows").lineSequence().any { "Window{" in it && RecordingAccessibilityOverlay.WINDOW_TITLE in it }

    /**
     * With the PIN cleared the keyguard is a swipe, which `wm dismiss-keyguard` drops on Samsung and the
     * emulator but not on a Pixel 9 Pro on Android 15 (Test Lab, 2026-11-08). Each step runs only if the
     * phone is still locked, and the field's focus is never touched, so the "no re-tap" claim still holds.
     */
    private fun unlock(): Boolean {
        shell("wm dismiss-keyguard")
        if (awaitLocked(locked = false, millis = 4_000)) return true
        // The rig occludes the keyguard; it asks the system to dismiss it, leaving focus alone.
        context.startActivity(
            Intent()
                .setClassName(TEST_PACKAGE, "com.envi.wispr.BubbleFieldsActivity")
                .putExtra(BubbleFieldsActivity.EXTRA_DISMISS_KEYGUARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        if (awaitLocked(locked = false, millis = 4_000)) return true
        shell("input keyevent KEYCODE_MENU")
        if (awaitLocked(locked = false, millis = 3_000)) return true
        val size = Regex("""(\d+)x(\d+)""").find(shell("wm size"))?.destructured
        val (w, h) = size?.let { (a, b) -> a.toInt() to b.toInt() } ?: (1080 to 2400)
        shell("input swipe ${w / 2} ${h * 4 / 5} ${w / 2} ${h / 5} 300")
        return awaitLocked(locked = false, millis = 4_000)
    }

    private fun awaitLocked(locked: Boolean, millis: Long = 10_000): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (keyguard.isKeyguardLocked == locked) return true
            Thread.sleep(250)
        }
        return false
    }

    /** Shell through UiAutomation WITHOUT suppressing accessibility services, or the paste service unbinds. */
    private fun shell(command: String): String {
        val descriptor = instrumentation
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }

    private companion object {
        const val TEST_PACKAGE = "com.envi.wispr.test"
        const val SERVICE = "com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService"
        const val PIN = "1234"
    }
}
