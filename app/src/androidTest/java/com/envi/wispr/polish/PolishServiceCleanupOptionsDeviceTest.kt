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
    fun bindWithPolishDisabled() {
        repository = ProviderConfigurationRepository(context)
        originalMode = repository.loadMode()
        repository.setMode(PolishMode.OFF)
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

    @Test
    fun allDisabledCleanupOptionsCrossTheProcessBoundary() {
        val completed = CountDownLatch(1)
        var result = ""

        service?.polish(
            "um keep thumbs up emoji comma literal",
            false,
            false,
            false,
            object : IPolishCallback.Stub() {
                override fun onResult(text: String?, engine: String?, latencyMs: Long) {
                    result = text.orEmpty()
                    completed.countDown()
                }

                override fun onError(message: String?) {
                    completed.countDown()
                }
            },
        )

        assertTrue("Polish callback timed out", completed.await(10, TimeUnit.SECONDS))
        assertEquals("um keep thumbs up emoji comma literal", result)
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
            val completed = CountDownLatch(1)
            var result = ""

            service?.polish(
                alias,
                false,
                false,
                false,
                object : IPolishCallback.Stub() {
                    override fun onResult(text: String?, engine: String?, latencyMs: Long) {
                        result = text.orEmpty()
                        completed.countDown()
                    }

                    override fun onError(message: String?) {
                        completed.countDown()
                    }
                },
            )

            assertTrue("Polish callback timed out", completed.await(10, TimeUnit.SECONDS))
            assertEquals(alias, result)
        } finally {
            runBlocking { terms.delete(record.id) }
        }
    }
}
