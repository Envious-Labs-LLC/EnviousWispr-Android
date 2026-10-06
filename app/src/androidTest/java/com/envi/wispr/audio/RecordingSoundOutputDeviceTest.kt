package com.envi.wispr.audio

import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.ui.SettingsActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Harness Contract: actual player adapter cancels interrupted output and ignores obsolete focus callbacks.
 * Uses real packaged players and preparation callbacks. Does not prove physical audibility.
 */
@RunWith(AndroidJUnit4::class)
class RecordingSoundOutputDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun onPreviewStart(pair: RecordingSoundPairing, action: () -> Unit) = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main.immediate) {
                val observed = async(start = CoroutineStart.UNDISPATCHED) {
                    RecordingSoundOutput.previewStarted.first { it == pair }
                    action()
                }
                RecordingSoundOutput.preview(context, pair)
                observed.await()
            }
        }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun startedFocusListener(): AudioManager.OnAudioFocusChangeListener {
        val clips = field(RecordingSoundOutput, "clips") as Set<*>
        val playing = clips.single { field(it!!, "playing") == true }!!
        return field(playing, "focusListener") as AudioManager.OnAudioFocusChangeListener
    }

    @Test fun interruptingALiveStartKeepsTheMatchingStopPrepared() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val token = "live-focus-regression"
            try {
                runBlocking {
                    withTimeout(5_000) {
                        withContext(Dispatchers.Main.immediate) {
                            val prepared = kotlinx.coroutines.CompletableDeferred<Unit>()
                            RecordingSoundOutput.admit(token)
                            RecordingSoundOutput.prepare(context, token, RecordingSoundPairing.WHISPER_TICK) {
                                runCatching {
                                    assertTrue(RecordingSoundOutput.play(token, RecordingSoundPairing.WHISPER_TICK, RecordingSoundMoment.START))
                                    startedFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                                    val clips = field(RecordingSoundOutput, "clips") as Set<*>
                                    assertEquals("only the future stop remains", 1, clips.size)
                                    assertEquals(true, field(clips.single()!!, "ready"))
                                    assertTrue(RecordingSoundOutput.play(token, RecordingSoundPairing.WHISPER_TICK, RecordingSoundMoment.STOP))
                                }.fold(onSuccess = { prepared.complete(Unit) }, onFailure = { prepared.completeExceptionally(it) })
                            }
                            prepared.await()
                        }
                    }
                }
            } finally { main {
                val cleanupToken = "$token-cleanup"
                RecordingSoundOutput.admit(cleanupToken)
                RecordingSoundOutput.finish(cleanupToken)
            } }
        }
    }

    @Test fun focusLossCancelsThePendingStopAndReleasesBothPlayers() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            try {
                onPreviewStart(RecordingSoundPairing.WHISPER_TICK) {
                    startedFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    assertNull(RecordingSoundOutput.previewing.value)
                    assertNull(field(RecordingSoundOutput, "previewStop"))
                    assertTrue((field(RecordingSoundOutput, "clips") as Set<*>).isEmpty())
                }
            } finally { main { RecordingSoundOutput.cancelPreview() } }
        }
    }

    @Test fun anOldFocusCallbackCannotCancelAReplacementPreview() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            try {
                onPreviewStart(RecordingSoundPairing.WHISPER_TICK) {
                    val oldListener = startedFocusListener()
                    RecordingSoundOutput.preview(context, RecordingSoundPairing.DUST_MOTE)
                    oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    assertEquals(RecordingSoundPairing.DUST_MOTE, RecordingSoundOutput.previewing.value)
                }
                runBlocking { withTimeout(5_000) { RecordingSoundOutput.previewStarted.first { it == RecordingSoundPairing.DUST_MOTE } } }
            } finally { main { RecordingSoundOutput.cancelPreview() } }
        }
    }
}
