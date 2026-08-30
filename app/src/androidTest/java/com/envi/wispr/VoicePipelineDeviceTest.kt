package com.envi.wispr

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.polish.IPolishCallback
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishService
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTermRecord
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.StructuredTermRestorer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-phone UAT. Place a 16 kHz mono s16le fixture in the app cache before running. */
@RunWith(AndroidJUnit4::class)
class VoicePipelineDeviceTest {
    private companion object {
        const val SURFACE_STATE_PREFERENCES = "dictation_surface_state"
        const val SURFACE_STATE_PHASE = "phase"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixturePath = File(context.cacheDir, "enviouswispr-uat.pcm").path
    private val asrConnected = CountDownLatch(1)
    private val polishConnected = CountDownLatch(1)
    private var asrService: IAsrService? = null
    private var polishService: IPolishService? = null
    private var asrBound = false
    private var polishBound = false

    private val asrConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            asrService = IAsrService.Stub.asInterface(binder)
            asrBound = true
            asrConnected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            asrService = null
            asrBound = false
        }
    }

    private val polishConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            polishService = IPolishService.Stub.asInterface(binder)
            polishBound = true
            polishConnected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            polishService = null
            polishBound = false
        }
    }

    @Before
    fun bindServices() {
        assumeTrue("Physical-phone fixture is missing", File(fixturePath).isFile)
        context.bindService(Intent(context, AsrService::class.java), asrConnection, Context.BIND_AUTO_CREATE)
        context.bindService(Intent(context, PolishService::class.java), polishConnection, Context.BIND_AUTO_CREATE)
        assertTrue("ASR service did not connect", asrConnected.await(15, TimeUnit.SECONDS))
        assertTrue("Polish service did not connect", polishConnected.await(15, TimeUnit.SECONDS))

        polishService?.warmUp()
        val deadline = System.currentTimeMillis() + 30_000
        while (polishService?.isReady != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("S1-mini did not become ready: ${polishService?.status}", polishService?.isReady == true)
    }

    @After
    fun unbindServices() {
        if (asrBound) context.unbindService(asrConnection)
        if (polishBound) context.unbindService(polishConnection)
    }

    @Test
    fun transcribesThenPolishesWithSavedCustomWords() {
        val terms = BuiltinVocabulary.withUserTerms(
            runBlocking { CustomTermRepository(context).list() }.map(CustomTermRecord::term),
        )
        val matcher = StructuredTermRestorer.compile(terms)

        val asrFinished = CountDownLatch(1)
        var rawText = ""
        var asrError = ""
        asrService?.transcribeFile(fixturePath, object : IAsrCallback.Stub() {
            override fun onResult(text: String?) {
                rawText = text.orEmpty()
                asrFinished.countDown()
            }

            override fun onError(message: String?) {
                asrError = message.orEmpty()
                asrFinished.countDown()
            }
        })
        assertTrue("ASR callback timed out", asrFinished.await(30, TimeUnit.SECONDS))
        assertTrue("ASR returned no text: $asrError", rawText.isNotBlank())

        val polishFinished = CountDownLatch(1)
        var polishedText = ""
        var engine = ""
        var latencyMs = -1L
        polishService?.polish(matcher.restore(rawText), true, true, false, object : IPolishCallback.Stub() {
            override fun onResult(text: String?, usedEngine: String?, measuredLatencyMs: Long) {
                polishedText = matcher.restore(text.orEmpty())
                engine = usedEngine.orEmpty()
                latencyMs = measuredLatencyMs
                polishFinished.countDown()
            }

            override fun onError(message: String?) {
                polishFinished.countDown()
            }
        })
        assertTrue("Polish callback timed out", polishFinished.await(30, TimeUnit.SECONDS))
        assertTrue("Unexpected engine: $engine", engine.startsWith("S1-mini by Superwhisper"))
        assertTrue("Saved product spelling was not applied: $polishedText", polishedText.contains("EnviousWispr"))
        assertTrue("Saved name spelling was not applied: $polishedText", polishedText.contains("Saurabh"))
        Log.i(
            "VoicePipelineDeviceTest",
            "rawChars=${rawText.length} engine=$engine latencyMs=$latencyMs polishedChars=${polishedText.length}"
        )
    }

    @Test
    fun launcherRecordsPhonePlaybackAndReachesClipboardStep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("logcat -c")

        val launchIntent = Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Thread.sleep(2_000)

        playFixtureThroughSpeaker()

        context.startActivity(
            Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_STOP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val deadline = System.currentTimeMillis() + 30_000
        var logs = ""
        while (System.currentTimeMillis() < deadline) {
            logs = shell("logcat -d -v brief -s DictationSession:I AsrService:I PolishService:I AudioCapture:I '*:S'")
            if (logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard")) break
            Thread.sleep(500)
        }

        assertTrue("Launcher path did not reach S1: $logs", logs.contains("Polish result received (S1-mini by Superwhisper (NPU)"))
        assertTrue(
            "Launcher path did not reach the clipboard/paste step: $logs",
            logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard"),
        )
        Log.i("VoiceLauncherDeviceTest", logs.lines().filter { it.contains("DictationSession") }.joinToString(" | "))
    }

    /**
     * PRODUCT OUTCOME. When this fails, every dictation from the Quick Settings tile, the Home
     * button, onboarding practice and the side button pressed outside a text field ends in a
     * failure haptic, a long toast and a shade notification saying auto-paste did not reach the
     * field. Four of the five entry points cannot pin a target, so that is ordinary use of the
     * product being reported as broken.
     *
     * Stages the alive-but-nothing-pinned case: only our own launcher is on screen, and the paste
     * service excludes our own package unconditionally, so nothing is pinned and the handoff is
     * NO_PINNED_TARGET while the service is perfectly alive. There was no field, so the clipboard
     * is the designed destination and the dictation SUCCEEDED.
     *
     * HONEST LIMIT: this proves the SILENCE on the no-field path, not the announcement on the
     * SERVICE_NOT_RUNNING path. That cause cannot be staged in-process without killing the
     * instrumentation or opening the private liveness field, which `validation-discipline.md`
     * RULE: a-test-seam-on-a-GUARD-is-a-bypass forbids. The announcement is the adb recipe in
     * `.claude/knowledge/device-testing.md`, whose step 6 dictates with the service unbound.
     *
     * The precondition is CONFIRMED after the run rather than assumed, per
     * `validation-discipline.md` RULE: verify-the-feature-not-the-crash. `pinTarget` falls back to
     * scanning every window, so a third-party editable field still focused behind our 1x1 launcher
     * pins successfully and the dictation reaches the editor. Reporting that as a red row would
     * accuse working code, so it reports SKIPPED with the observed handoff named. The handoff also
     * proves this row is not vacuous: an absent notification means nothing unless the session got
     * as far as deciding it had no field to insert into.
     */
    @Test
    fun aDictationWithNoFieldToInsertIntoIsNotReportedToTheUserAsAFailure() {
        assumeTrue(
            "Notifications are not permitted, so an announcement could not be posted either way " +
                "and this row could not tell silence from a blocked notification",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        assumeTrue(
            "The accessibility service is not enabled",
            shell("settings get secure enabled_accessibility_services")
                .contains("com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService"),
        )
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        // The session's own notification is dismissed when the session ends, so an empty tray at
        // the end of this run is the state to assert against.
        notificationManager.cancel(DictationNotificationController.NOTIFICATION_ID)
        // The ring buffer wraps, so observedHandoff() must read THIS run
        // (`android-tooling.md` RULE: clear-logcat-before-you-measure).
        shell("logcat -c")

        val listening = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val stopRequested = AtomicBoolean(false)
        val surfaceState = context.getSharedPreferences(SURFACE_STATE_PREFERENCES, Context.MODE_PRIVATE)
        // The session owner writes each phase itself, so the test never guesses when it is finished.
        val phaseListener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            if (key != SURFACE_STATE_PHASE) return@OnSharedPreferenceChangeListener
            when (store.getString(key, null)) {
                "LISTENING" -> listening.countDown()
                "IDLE" -> if (stopRequested.get()) idle.countDown()
            }
        }
        surfaceState.registerOnSharedPreferenceChangeListener(phaseListener)
        try {
            context.startActivity(
                Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue(
                "The recorder never reported LISTENING; phase was " +
                    surfaceState.getString(SURFACE_STATE_PHASE, null),
                listening.await(20, TimeUnit.SECONDS),
            )

            playFixtureThroughSpeaker()

            stopRequested.set(true)
            context.startActivity(
                Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                    .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_STOP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            assertTrue(
                "The session never returned to IDLE; phase was " +
                    surfaceState.getString(SURFACE_STATE_PHASE, null),
                idle.await(90, TimeUnit.SECONDS),
            )
        } finally {
            surfaceState.unregisterOnSharedPreferenceChangeListener(phaseListener)
        }

        // The session owner names its own handoff, so the staged precondition is READ rather than
        // assumed. Anything but NO_PINNED_TARGET means the phone was not in the state this row is
        // about.
        val handoff = observedHandoff()
        assumeTrue(
            "Nothing could be staged: the dictation reported handoff=$handoff, so an editable " +
                "field was focused behind the launcher and the words went where they should",
            handoff == "NO_PINNED_TARGET",
        )

        // NOT a check for one id. The durable fallback notification was DELETED in the messaging
        // rework, so asserting its absence would be a green row about nothing
        // (`testing-philosophy.md` RULE: the-rig-decides-where-a-test-lives-not-the-subject). What
        // is asserted instead is the property that outlives it: after an ordinary dictation with no
        // field, this app has left NOTHING in the user's shade. That goes red if any durable
        // announcement comes back, whatever id it chooses.
        val ourNotifications = notificationManager.activeNotifications
            .filter { it.packageName == context.packageName }
        val titles = ourNotifications.joinToString {
            it.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty() + " (id=${it.id})"
        }
        assertEquals(
            "A dictation with no field to insert into left something in the shade: $titles. There " +
                "was no field, the clipboard is the designed destination, and this is what every " +
                "tile and Home-button dictation does.",
            emptyList<String>(),
            ourNotifications.map { "id=${'$'}{it.id}" },
        )
        Log.i("VoiceFallbackDeviceTest", "handoff=$handoff shadeAfterDictation=empty")
    }

    /** The handoff the session owner logged for the run that just finished, or `"none"`. */
    private fun observedHandoff(): String {
        val logs = shell("logcat -d -v brief -s DictationSession:I '*:S'")
        return Regex("\\(handoff=([A-Z_]+)\\)").findAll(logs).lastOrNull()?.groupValues?.get(1)
            ?: "none"
    }

    private fun playFixtureThroughSpeaker() {
        val pcm = File(fixturePath).readBytes()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val testVolume = minOf(5, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testVolume, 0)
            assertTrue("Phone playback could not be queued", audioTrack.write(pcm, 0, pcm.size) > 0)
            audioTrack.play()
            // The length of the clip at 16 kHz mono s16le, not a settle.
            Thread.sleep(pcm.size / 32L + 750)
        } finally {
            audioTrack.stop()
            audioTrack.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
