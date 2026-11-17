package com.envi.wispr.paste

import android.app.Activity
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.PasteTargetActivity
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.HistoryRow
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.insertion.WordsKept
import com.envi.wispr.ui.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PRODUCT OUTCOME (#11): EnviousWispr inserts into password fields like any other editable field,
 * leaving prior clipboard contents untouched, and keeps copy-only fallback when a target vanishes.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldInsertionDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dao by lazy { EnviousWisprDatabase.get(context).transcriptDao() }
    private var savedA11yServices = ""
    private var savedA11yEnabled = ""
    private var a11ySnapshotTaken = false
    private var savedClip: ClipData? = null
    private var clipSnapshotTaken = false
    private val syntheticRowIds = mutableListOf<Long>()

    @Before
    fun stage() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        savedA11yServices = shell("settings get secure enabled_accessibility_services").trim()
        savedA11yEnabled = shell("settings get secure accessibility_enabled").trim()
        a11ySnapshotTaken = true
        if (PasteAccessibilityService.isBound.value) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
            runBlocking { withTimeoutOrNull(10_000) { PasteAccessibilityService.isBound.first { !it } } }
        }
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        val bound = runBlocking { withTimeoutOrNull(30_000) { PasteAccessibilityService.isBound.first { it } } }
        assertTrue("staging: the paste service never bound", bound == true)

        inForeground { clipboard ->
            savedClip = clipboard.primaryClip
            clipSnapshotTaken = true
        }
    }

    @After
    fun restore() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try { block() } catch (error: Throwable) { failures += "$name: ${error.message}" }
        }
        step("finish target") {
            context.sendBroadcast(Intent(PasteTargetActivity.ACTION_FINISH).setPackage(TEST_PACKAGE))
        }
        step("synthetic history rows") {
            runBlocking {
                syntheticRowIds.forEach { id -> dao.deleteById(id) }
                syntheticRowIds.clear()
            }
        }
        step("accessibility settings") {
            if (a11ySnapshotTaken) {
                restoreSecure("enabled_accessibility_services", savedA11yServices)
                restoreSecure("accessibility_enabled", savedA11yEnabled)
                a11ySnapshotTaken = false
            }
        }
        step("clipboard") {
            if (clipSnapshotTaken) {
                inForeground { clipboard ->
                    val previous = savedClip
                    if (previous != null) {
                        clipboard.setPrimaryClip(previous)
                    } else {
                        clipboard.clearPrimaryClip()
                    }
                }
                clipSnapshotTaken = false
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("cleanup failed: ${failures.joinToString("; ")}")
    }

    @Test
    fun passwordFieldReceivesDictatedTextNormally() {
        val sentinel = "sentinel-${UUID.randomUUID()}"
        inForeground { clipboard ->
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", sentinel))
        }

        val payload = "hello password box"
        val rowId = insertPendingRow(payload)

        val token = UUID.randomUUID().toString().replace("-", "")
        val readyLatch = CountDownLatch(1)
        val textLatch = CountDownLatch(1)
        val receivedBroadcastText = AtomicReference<String>("")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getStringExtra(PasteTargetActivity.EXTRA_RIG_TOKEN) != token) return
                when (intent.action) {
                    PasteTargetActivity.ACTION_READY -> readyLatch.countDown()
                    PasteTargetActivity.ACTION_TEXT_UPDATED -> {
                        val txt = intent.getStringExtra(PasteTargetActivity.EXTRA_TEXT).orEmpty()
                        if (txt == payload) {
                            receivedBroadcastText.set(txt)
                            textLatch.countDown()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PasteTargetActivity.ACTION_READY)
            addAction(PasteTargetActivity.ACTION_TEXT_UPDATED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        try {
            launchPasswordTarget(token, readyLatch)
            assertEquals("staging: password field did not pin", DictationTargetPin.PINNED, PasteAccessibilityService.pinTargetForDictation())

            val handoff = PasteAccessibilityService.pasteWhenTargetReturns(
                row = rowHandle(rowId),
                text = payload,
                policy = ClipboardInsertionPolicy(
                    smartInsertion = false,
                    autoCopyToClipboard = false,
                    restoreClipboardAfterPaste = true,
                ),
            )
            assertEquals(InsertionHandoff.SCHEDULED, handoff)

            assertTrue("password editor never reported receiving text", textLatch.await(10, TimeUnit.SECONDS))
            assertEquals(payload, receivedBroadcastText.get())
            val completed = runBlocking {
                withTimeoutOrNull(10_000) {
                    dao.observeAll().first { rows ->
                        val r = rows.firstOrNull { it.id == rowId }
                        r != null && r.insertionResult != "pending"
                    }.first { it.id == rowId }
                }
            }
            assertNotNull("history row never finalized insertion outcome", completed)
            assertTrue(
                "unexpected insertion result ${completed!!.insertionResult}",
                completed.insertionResult in setOf(InsertionResults.COMMITTED, InsertionResults.PASTED),
            )
            assertEquals(payload, readTestFile(PasteTargetActivity.RECEIPT_NAME))

            var clipAfter: String? = null
            inForeground { clipboard ->
                clipAfter = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertNotEquals("dictated password text should not remain on clipboard", payload, clipAfter)
            assertEquals(sentinel, clipAfter)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun missingTargetStillUsesOrdinaryCopyOnlyFallback() {
        val payload = "fallback check text"
        val rowId = insertPendingRow(payload)
        val token = UUID.randomUUID().toString().replace("-", "")
        val readyLatch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == PasteTargetActivity.ACTION_READY &&
                    intent.getStringExtra(PasteTargetActivity.EXTRA_RIG_TOKEN) == token
                ) {
                    readyLatch.countDown()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(PasteTargetActivity.ACTION_READY), Context.RECEIVER_EXPORTED)
        try {
            launchPasswordTarget(token, readyLatch)
            assertEquals(DictationTargetPin.PINNED, PasteAccessibilityService.pinTargetForDictation())

            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val departedEvent = automation.executeAndWaitForEvent(
                { context.sendBroadcast(Intent(PasteTargetActivity.ACTION_FINISH).setPackage(TEST_PACKAGE)) },
                { event ->
                    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    ) {
                        return@executeAndWaitForEvent false
                    }
                    val activeRoot = automation.rootInActiveWindow ?: return@executeAndWaitForEvent true
                    try {
                        activeRoot.packageName?.toString() != TEST_PACKAGE
                    } finally {
                        @Suppress("DEPRECATION")
                        activeRoot.recycle()
                    }
                },
                10_000L,
            )
            @Suppress("DEPRECATION")
            departedEvent.recycle()

            val handoff = PasteAccessibilityService.pasteWhenTargetReturns(
                row = rowHandle(rowId),
                text = payload,
                policy = ClipboardInsertionPolicy(smartInsertion = false, autoCopyToClipboard = false),
            )
            assertEquals(InsertionHandoff.SCHEDULED, handoff)

            val completed = runBlocking {
                withTimeoutOrNull(10_000) {
                    dao.observeAll().first { rows ->
                        val r = rows.firstOrNull { it.id == rowId }
                        r != null && r.insertionResult != "pending"
                    }.first { it.id == rowId }
                }
            }
            assertNotNull("history row never finalized fallback outcome", completed)
            assertEquals(InsertionResults.COPY_ONLY, completed!!.insertionResult)

            var clipAfter: String? = null
            inForeground { clipboard ->
                clipAfter = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals(payload, clipAfter)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun launchPasswordTarget(token: String, readyLatch: CountDownLatch) {
        shell("run-as $TEST_PACKAGE rm -f files/${PasteTargetActivity.RECEIPT_NAME} files/${PasteTargetActivity.READY_NAME}")
        context.startActivity(
            Intent()
                .setClassName(TEST_PACKAGE, "com.envi.wispr.PasteTargetActivity")
                .putExtra(PasteTargetActivity.EXTRA_PASSWORD, true)
                .putExtra(PasteTargetActivity.EXTRA_NOTIFY_PACKAGE, context.packageName)
                .putExtra(PasteTargetActivity.EXTRA_RIG_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        assertTrue("staging: password target never signaled READY", readyLatch.await(15, TimeUnit.SECONDS))
        val observed = runBlocking {
            withTimeoutOrNull(15_000) {
                PasteAccessibilityService.observedField.first {
                    it?.packageName == TEST_PACKAGE && it.passwordField
                }
            }
        }
        assertNotNull("staging: paste service never observed the password target", observed)
        instrumentation.waitForIdleSync()
    }

    private fun insertPendingRow(text: String): Long = runBlocking {
        dao.insert(
            TranscriptEntity(
                originalText = text,
                finalText = text,
                createdAtMs = System.currentTimeMillis(),
                durationMs = 0L,
                speechEngine = "Test",
                polishEngine = "Off",
                polishLatencyMs = 0L,
                insertionResult = "pending",
                status = TranscriptEntity.STATUS_READY_FOR_INSERTION,
                takeId = "pwd-${UUID.randomUUID()}",
            ),
        ).also { syntheticRowIds += it }
    }

    private fun rowHandle(rowId: Long) = object : HistoryRow {
        override fun resolveOnQueue(): Long = rowId
        override val savedNow: Boolean = true
        override fun wordsKept(): WordsKept = WordsKept.KEPT
    }

    private fun inForeground(block: (ClipboardManager) -> Unit) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val focusLatch = CountDownLatch(1)
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                if (decor.hasWindowFocus()) {
                    focusLatch.countDown()
                } else {
                    lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
                    listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                        if (focused) {
                            decor.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                            focusLatch.countDown()
                        }
                    }
                    decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                }
            }
            assertTrue("staging: foreground settings window never focused", focusLatch.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                block(activity.getSystemService(ClipboardManager::class.java)!!)
            }
        }
    }

    private fun readTestFile(name: String): String =
        shell("run-as $TEST_PACKAGE cat files/$name 2>/dev/null || true")

    private fun restoreSecure(key: String, value: String) {
        if (value == "null" || value.isEmpty()) shell("settings delete secure $key") else shell("settings put secure $key $value")
        val expected = if (value.isEmpty()) "null" else value
        val now = shell("settings get secure $key").trim()
        check(now == expected) { "$key reads '$now', expected '$expected'" }
    }

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
    }
}
