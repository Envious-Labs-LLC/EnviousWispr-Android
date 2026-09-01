package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Product Outcome: what crosses the process boundary is what the engine acts on. The cleanup
 * switches and the policy both ride on the request; the engine reads no preference of its own, so
 * a stored mode the engine process may have cached cannot override the policy the session sent.
 * Needs no model, so it runs on the emulator as well as the phone.
 */
@RunWith(AndroidJUnit4::class)
class PolishServiceCleanupOptionsDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connected = CountDownLatch(1)
    private lateinit var repository: ProviderConfigurationRepository
    private lateinit var originalMode: PolishMode
    private var service: IPolishService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPolishService.Stub.asInterface(binder)
            bound = true
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    @Before
    fun bindWithAStoredModeTheEngineMustIgnore() {
        repository = ProviderConfigurationRepository(context)
        originalMode = repository.loadMode()
        // The stored mode says "This phone". Every request below sends Off. The engine must act on
        // the request, which is the whole contract of issue #69.
        repository.setMode(PolishMode.OFFLINE_S1)
        assertTrue(
            context.bindService(Intent(context, PolishService::class.java), connection, Context.BIND_AUTO_CREATE),
        )
        assertTrue("PolishService did not connect", connected.await(10, TimeUnit.SECONDS))
    }

    @After
    fun restoreModeAndUnbind() {
        if (bound) context.unbindService(connection)
        repository.setMode(originalMode)
    }

    private fun polishOff(requestId: Long, text: String, removeFillers: Boolean, spokenEmoji: Boolean, spokenPunctuation: Boolean): PolishOutcome {
        val completed = CountDownLatch(1)
        var outcome: PolishOutcome? = null
        service?.polishRequest(
            requestId,
            text,
            removeFillers,
            spokenEmoji,
            spokenPunctuation,
            PolishPolicy.Off,
            object : IPolishCallback.Stub() {
                override fun onOutcome(delivered: PolishOutcome?) {
                    outcome = delivered
                    completed.countDown()
                }

                override fun onResult(text: String?, engine: String?, latencyMs: Long) = Unit

                override fun onError(message: String?) = Unit
            },
        )
        assertTrue("Polish callback timed out", completed.await(10, TimeUnit.SECONDS))
        return checkNotNull(outcome) { "no outcome delivered" }
    }

    @Test
    fun allDisabledCleanupOptionsCrossTheProcessBoundary() {
        val outcome = polishOff(11L, "um keep thumbs up emoji comma literal", false, false, false)
        assertEquals("um keep thumbs up emoji comma literal", outcome.text)
        assertEquals(11L, outcome.requestId)
    }

    @Test
    fun thePolicyOnTheRequestBeatsTheModeStoredInPreferences() {
        val outcome = polishOff(12L, "hello world", true, true, false)
        assertEquals(PolishReason.OFF, outcome.reason)
        assertEquals(PolishEngineLabels.OFF, outcome.engine)
        assertEquals("hello world", outcome.text)
    }

    @Test
    fun aCloudPolicyWithNoSelectionFailsOpenToTheDeterministicText() {
        val completed = CountDownLatch(1)
        var outcome: PolishOutcome? = null
        service?.polishRequest(
            13L,
            "um hello world",
            true,
            true,
            false,
            PolishPolicy.CloudUnconfigured,
            object : IPolishCallback.Stub() {
                override fun onOutcome(delivered: PolishOutcome?) {
                    outcome = delivered
                    completed.countDown()
                }

                override fun onResult(text: String?, engine: String?, latencyMs: Long) = Unit

                override fun onError(message: String?) = Unit
            },
        )
        assertTrue("Polish callback timed out", completed.await(10, TimeUnit.SECONDS))
        val delivered = checkNotNull(outcome)
        assertEquals(PolishReason.CLOUD_NOT_CONFIGURED, delivered.reason)
        assertEquals(PolishEngineLabels.DETERMINISTIC, delivered.engine)
        assertEquals("hello world", delivered.text)
    }

    @Test
    fun polishProcessDoesNotReloadLiveVocabulary() {
        val terms = CustomTermRepository(context)
        val suffix = System.nanoTime().toString()
        val alias = "snapshot only alias $suffix"
        val record = runBlocking {
            terms.add(
                CustomTerm(
                    spelling = "SnapshotOnlyTerm$suffix",
                    aliases = listOf(alias),
                ),
            )
        }
        try {
            assertEquals(alias, polishOff(14L, alias, false, false, false).text)
        } finally {
            runBlocking { terms.delete(record.id) }
        }
    }
}
