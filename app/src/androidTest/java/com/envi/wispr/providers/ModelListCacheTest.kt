package com.envi.wispr.providers

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drift Guard: the JSON round trip of the per-provider model list (#84), its clear, and a failed commit keeping the old blob. */
@RunWith(AndroidJUnit4::class)
class ModelListCacheTest {
    private lateinit var context: Context
    private lateinit var cache: ModelListCache

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ModelListCache.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        cache = ModelListCache(context)
    }

    private val entry = ModelListCache.Entry(
        fetchedAt = 1_725_000_000_000L,
        models = listOf(
            DiscoveredModel("gpt-4.1-mini", "Gpt 4.1 Mini", ModelAccess.AVAILABLE, true),
            DiscoveredModel("gpt-5.6-sol", "Gpt 5.6 Sol", ModelAccess.UNAVAILABLE, false),
            DiscoveredModel("gpt-9", "Gpt 9", ModelAccess.UNVERIFIED, false),
        ),
    )

    @Test fun roundTripsPerProviderAndClears() {
        assertNull(cache.read(Provider.OPENAI))
        assertEquals(true, cache.write(Provider.OPENAI, entry))
        assertEquals(entry, cache.read(Provider.OPENAI))
        assertNull(cache.read(Provider.GEMINI))
        cache.clear(Provider.OPENAI)
        assertNull(cache.read(Provider.OPENAI))
    }

    @Test fun aFailedCommitKeepsTheOldBlob_failingFake() {
        cache.write(Provider.CLAUDE, entry)
        val fragile = ModelListCache(FailingCommitPreferences(context.getSharedPreferences(ModelListCache.PREFERENCES, Context.MODE_PRIVATE)))
        assertFalse(fragile.write(Provider.CLAUDE, entry.copy(fetchedAt = 1L, models = emptyList())))
        assertEquals(entry, cache.read(Provider.CLAUDE))
    }

    private class FailingCommitPreferences(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by real.edit() {
            override fun commit(): Boolean = false
            override fun apply() = Unit
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
        }
    }
}
