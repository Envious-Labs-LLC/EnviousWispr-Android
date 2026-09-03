package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Product Outcome. When this fails, a user dictating in one of the 24 non-English languages Parakeet v3
 * decodes gets English number, date and money rules applied to their own words.
 *
 * **This exists because a JVM test cannot reach the defect it guards, and a device test that calls the
 * detector directly cannot either.** ML Kit bootstraps from a `ContentProvider` with no
 * `android:process`, so Android runs it in the DEFAULT process only. The engine runs in `:polish`, where
 * it never ran, and the shipped feature was dead there while 441 unit tests, four review rounds and a
 * hardware probe that called `MlKitLanguageDetector` directly were all green — instrumentation runs in
 * the default process, where the provider HAD run. Measured 2026-09-03 (#107).
 *
 * So the assertion is made through the AIDL boundary, against the real engine, in its own process, with
 * polish OFF — the policy that runs deterministic cleanup and nothing else.
 *
 * **The two assertions show that the real engine preserves the Dutch collision while still running
 * deterministic cleanup. They do not attribute that result to ML Kit by themselves** — a hard-coded Dutch
 * bypass would pass both. The engine-process `language=` receipt supplies that attribution, and the
 * hardware section of the plan records it.
 */
@RunWith(AndroidJUnit4::class)
class EnginePolishLanguageTest {

    private companion object {
        /** Dutch. `ten` is a real Dutch word AND an English number word, which is the collision. */
        const val DUTCH = "Dit is ten minste duidelijk."

        /** What cleanup produces when no language is established: the 2026-09-02 regression. */
        const val DUTCH_REWRITTEN_AS_ENGLISH = "Dit is 10 minste duidelijk."
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connected = CountDownLatch(1)
    private var engine: IPolishService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            engine = IPolishService.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
        }
    }

    /**
     * `assertTrue`, NOT `assumeTrue`. A skip is for a precondition the harness cannot STAGE; the engine
     * failing to start is the SUBJECT failing, and the regression this suite exists for — an ML Kit
     * initialization crash in `onCreate` — would present exactly as a service that will not connect
     * (review round 6). A skip there would hide the defect the suite was written to catch.
     */
    @Before fun bindEngine() {
        bound = context.bindService(Intent(context, PolishService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue("Could not bind the polish engine", bound)
        assertTrue("The polish engine did not connect within 60s", connected.await(60, TimeUnit.SECONDS))
    }

    @After fun unbindEngine() {
        if (bound) context.unbindService(connection)
    }

    /**
     * The row that matters. Unchanged Dutch is only meaningful against what cleanup does WITHOUT a
     * language: [DUTCH_REWRITTEN_AS_ENGLISH], which is what this phone actually produced on 2026-09-02
     * and again before the ML Kit initialization fix. Those two constants differing is CONTEXT for the
     * reader, not a check — a test comparing them would observe neither the engine nor any regression,
     * which is why the row that did exactly that was deleted in review round 6.
     */
    @Test fun theEngineKeepsDutchWordsThatLookLikeEnglishNumbers() {
        assertEquals("The engine rewrote Dutch with English number rules", DUTCH, polish(1071L, DUTCH))
    }

    /**
     * The no-op control, and nothing more. `uh` is removed at EVERY language state, so this row says
     * only that the engine is still cleaning; it establishes no language and its earlier name claimed it
     * did (review round 8).
     */
    @Test fun theEngineStillRunsDeterministicCleanup() {
        assertEquals("The engine stopped cleaning altogether", "hello there", polish(1072L, "uh hello there"))
    }

    /** Runs one request through the real engine with polish OFF and returns its text. */
    private fun polish(requestId: Long, raw: String): String {
        val answered = CountDownLatch(1)
        var text = ""
        var failure = ""
        engine?.polishRequest(requestId, raw, true, true, false, PolishPolicy.Off, object : IPolishCallback.Stub() {
            override fun onOutcome(outcome: PolishOutcome?) {
                text = outcome?.text.orEmpty()
                answered.countDown()
            }

            override fun onResult(t: String?, e: String?, latencyMs: Long) = Unit

            override fun onError(message: String?) {
                failure = message.orEmpty()
                answered.countDown()
            }
        })
        // Same reasoning as `bindEngine`: an engine that hangs or errors is the subject failing.
        assertTrue("The engine never answered request $requestId", answered.await(60, TimeUnit.SECONDS))
        assertTrue("The engine reported an error: $failure", failure.isEmpty())
        return text
    }
}
