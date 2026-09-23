package com.envi.wispr.providers.ui

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.envi.wispr.polish.PolishFailure
import com.envi.wispr.providers.DiscoveredModel
import com.envi.wispr.providers.ModelAccess
import com.envi.wispr.providers.ModelListCache
import com.envi.wispr.providers.PolishMode
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.ProviderDiscovery
import com.envi.wispr.providers.ProviderKeyCheck
import com.envi.wispr.providers.ProviderModelDiscoverer
import com.envi.wispr.providers.SecretStore
import com.envi.wispr.providers.SelfHostedProtocol
import com.envi.wispr.providers.capabilities
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Product Outcome (#218): the AI Polish tab keeps its promises after it moved into its own view model. The
 * mode the user tapped last is the one the phone keeps, every save reports its own completion, and a model
 * list lands only on the page it was asked for.
 *
 * Main is one real thread, as on a phone, and the view model's own `Dispatchers.IO` is real too. Every IO
 * step a row is about is held by a latch in a fake and released by the row; the row then waits for that
 * tap's own coroutine to finish (its `Job`), which is a completion signal, not a guess at a time. Every
 * wait has a deadline and fails loudly when the signal never comes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PolishSettingsViewModelTest {
    private val mainThread: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main").apply { isDaemon = true } }
    private val main = mainThread.asCoroutineDispatcher()
    private val settingsPrefs = FakePreferences()
    private val cachePrefs = FakePreferences()
    private val secrets = FakeSecrets()
    private val discoverer = GatedDiscoverer()
    private lateinit var viewModel: PolishSettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        discoverer.releaseAll()
        settingsPrefs.releaseAll()
        cachePrefs.releaseAll()
        if (::viewModel.isInitialized) {
            val scope = viewModel.viewModelScope.coroutineContext.job
            scope.cancel()
            // Every coroutine still resuming through Main finishes before Main is reset under it.
            runBlocking { withTimeout(DEADLINE_MS) { scope.join() } }
        }
        mainThread.shutdown()
        check(mainThread.awaitTermination(DEADLINE_MS, TimeUnit.MILLISECONDS)) { "Main never went idle" }
        Dispatchers.resetMain()
    }

    /** Builds the view model and returns the job of its initial load, the one coroutine `init` launches. */
    private fun build(): Job {
        val repository = ProviderConfigurationRepository(settingsPrefs, secrets) { _, _ -> ProviderKeyCheck.Accepted }
        return onMain {
            viewModel = PolishSettingsViewModel(repository, discoverer, ModelListCache(cachePrefs))
            viewModel.viewModelScope.coroutineContext.job.children.single()
        }
    }

    /** Runs [block] on Main, the way a tap arrives, and returns its result with the one coroutine it launched. */
    private fun <T> tap(block: () -> T): Pair<T, Job> = onMain {
        val scope = viewModel.viewModelScope.coroutineContext.job
        val before = scope.children.toSet()
        val result = block()
        result to (scope.children.toSet() - before).single()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    private fun awaitDone(vararg jobs: Job) = runBlocking {
        withTimeout(DEADLINE_MS) { jobs.forEach { it.join() } }
    }

    private fun awaitSettings(predicate: (ProviderSettingsUiState) -> Boolean): ProviderSettingsUiState =
        runBlocking { withTimeout(DEADLINE_MS) { viewModel.settings.first(predicate) } }

    @Test fun theInitialLoadPublishesBeforeTheFirstWriteItRacedWith() {
        val read = settingsPrefs.holdNextRead()
        val load = build()
        read.awaitEntered()
        // The load holds the lock and is parked inside its read; the tap queues behind it.
        val (sequence, write) = tap { viewModel.setPolishMode(PolishMode.OFF) }
        read.release()
        awaitDone(load, write)
        val settings = viewModel.settings.value
        assertEquals(1, sequence)
        assertEquals("the initial load landed over the write it raced with", sequence, settings.writeSequence)
        assertEquals(PolishMode.OFF, settings.mode)
        assertFalse(settings.loading)
    }

    /**
     * REVERT: remove `providerSettingsMutex.withLock` from `updateProviderSettings`. The second tap then
     * commits while the first is parked, the first commits last, and the phone keeps the mode tapped first.
     */
    @Test fun twoRapidModeTapsPersistTheLastOneTapped() {
        awaitDone(build())
        val firstCommit = settingsPrefs.holdNextCommit()
        val (first, firstWrite) = tap { viewModel.setPolishMode(PolishMode.OFF) }
        // Asserted, not assumed: the first tap's write took the lock on Main and is parked in its commit
        // before the second tap is made.
        firstCommit.awaitEntered()
        val (second, secondWrite) = tap { viewModel.setPolishMode(PolishMode.OFFLINE_S1) }
        firstCommit.release()
        awaitDone(firstWrite, secondWrite)
        assertEquals(listOf(1, 2), listOf(first, second))
        assertEquals("the mode tapped last is not the one the phone kept", PolishMode.OFFLINE_S1.name, settingsPrefs.values["mode"])
        assertEquals(PolishMode.OFFLINE_S1, viewModel.settings.value.mode)
        assertEquals("an older write's completion landed after the last tap's", second, viewModel.settings.value.writeSequence)
    }

    @Test fun eachCompletedWritePublishesItsOwnSequenceWithItsMessage() {
        awaitDone(build())
        val name = Provider.OPENAI.capabilities().displayName
        val (saved, _) = tap {
            viewModel.saveProviderSettings(Provider.OPENAI, "gpt-test", null, "sk-test-key", SelfHostedProtocol.OPENAI_COMPATIBLE)
        }
        val afterSave = awaitSettings { it.writeSequence == saved }
        assertEquals("$name saved", afterSave.message)
        assertTrue(afterSave.credentialStored)
        val (removed, _) = tap { viewModel.removeProviderKey(Provider.OPENAI) }
        val afterRemove = awaitSettings { it.writeSequence == removed }
        assertEquals(saved + 1, removed)
        assertEquals("$name removed", afterRemove.message)
        assertFalse(afterRemove.credentialStored)
    }

    /** REVERT: drop `if (appliesNow())` from the empty-list branch of `discoverModels`. */
    @Test fun anEmptyListFromAnOlderCheckLeavesTheNewerCheckShowing() {
        awaitDone(build())
        val older = discoverer.hold("draft-1")
        val newer = discoverer.hold("draft-2")
        val (_, olderJob) = tap { viewModel.discoverModels(Provider.OPENAI, "draft-1") }
        val (latest, _) = tap { viewModel.discoverModels(Provider.OPENAI, "draft-2") }
        older.awaitEntered()
        newer.awaitEntered()
        older.release(ProviderDiscovery.Listed(emptyList(), fetchedAt = 1L))
        awaitDone(olderJob)
        val state = viewModel.providerDiscovery.value
        assertEquals(latest, state.sequence)
        assertEquals(ProviderDiscoveryUiState.Phase.CHECKING, state.phase)
        assertNull("an older Check's empty list replaced the newer Check", state.line)
    }

    /** REVERT: drop `if (appliesNow())` from the refused branch of `discoverModels`. */
    @Test fun aRefusalForAPageThatClosedNeverReachesTheOpenPage() {
        awaitDone(build())
        val closed = discoverer.hold("draft-openai")
        val open = discoverer.hold("draft-claude")
        val (_, closedJob) = tap { viewModel.discoverModels(Provider.OPENAI, "draft-openai") }
        tap { viewModel.discoverModels(Provider.CLAUDE, "draft-claude") }
        closed.awaitEntered()
        open.awaitEntered()
        closed.release(ProviderDiscovery.Refused(ProviderKeyCheck.Unverified(PolishFailure.BAD_REQUEST)))
        awaitDone(closedJob)
        val state = viewModel.providerDiscovery.value
        assertEquals(Provider.CLAUDE, state.provider)
        assertEquals(ProviderDiscoveryUiState.Phase.CHECKING, state.phase)
        assertNull("a refusal for a closed page reached the open page", state.line)
    }

    /** REVERT: remove `if (!appliesNow()) return@launch` before the final publication in `discoverModels`. */
    @Test fun aListForAPageThatClosedNeverReachesTheOpenPage() {
        awaitDone(build())
        val closed = discoverer.hold("draft-openai")
        val open = discoverer.hold("draft-claude")
        val (_, closedJob) = tap { viewModel.discoverModels(Provider.OPENAI, "draft-openai") }
        tap { viewModel.discoverModels(Provider.CLAUDE, "draft-claude") }
        closed.awaitEntered()
        open.awaitEntered()
        closed.release(ProviderDiscovery.Listed(listOf(model("gpt-test")), fetchedAt = 1L))
        awaitDone(closedJob)
        val state = viewModel.providerDiscovery.value
        assertEquals("another provider's list landed on the open page", Provider.CLAUDE, state.provider)
        assertEquals(ProviderDiscoveryUiState.Phase.CHECKING, state.phase)
        assertTrue(state.models.isEmpty())
    }

    /**
     * REVERT: make the stored-key cache write in `discoverModels` also require the active page (write only
     * when `appliesNow()`). The saved key's fresh list is then lost because the user opened another page.
     */
    @Test fun aStoredKeyListRefreshesItsCacheAfterItsPageCloses() {
        secrets.put(Provider.OPENAI, "stored-openai")
        awaitDone(build())
        val closed = discoverer.hold("stored-openai")
        val open = discoverer.hold("draft-claude")
        val (_, closedJob) = tap { viewModel.discoverModels(Provider.OPENAI, null) }
        tap { viewModel.discoverModels(Provider.CLAUDE, "draft-claude") }
        closed.awaitEntered()
        open.awaitEntered()
        closed.release(ProviderDiscovery.Listed(listOf(model("gpt-test")), fetchedAt = 7L))
        awaitDone(closedJob)
        val cached = ModelListCache(cachePrefs).read(Provider.OPENAI)
        assertNotNull("the saved key's list never reached its cache", cached)
        assertEquals(listOf("gpt-test"), cached!!.models.map { it.id })
        assertEquals(Provider.CLAUDE, viewModel.providerDiscovery.value.provider)
    }

    /** REVERT: drop the `isLatest` condition from the stored-key cache write in `discoverModels`. */
    @Test fun anOlderStoredKeyListNeverOverwritesTheCache() {
        secrets.put(Provider.OPENAI, "stored-openai")
        awaitDone(build())
        val older = discoverer.hold("stored-openai")
        val (_, olderJob) = tap { viewModel.discoverModels(Provider.OPENAI, null) }
        older.awaitEntered()
        val newer = discoverer.hold("stored-openai")
        tap { viewModel.discoverModels(Provider.OPENAI, null) }
        newer.awaitEntered()
        older.release(ProviderDiscovery.Listed(listOf(model("gpt-old")), fetchedAt = 1L))
        awaitDone(olderJob)
        assertNull("an older Check wrote the cache under a newer one", ModelListCache(cachePrefs).read(Provider.OPENAI))
    }

    /** The deadlines are real: a job that never finishes fails the row instead of hanging the suite. */
    @Test fun aSignalThatNeverComesFailsInsteadOfHanging() {
        val never = Job()
        val failure = runCatching { runBlocking { withTimeout(10) { never.join() } } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        val hold = Hold(deadlineMs = 10)
        assertTrue(runCatching { hold.awaitEntered() }.exceptionOrNull() is IllegalStateException)
    }

    private fun model(id: String) = DiscoveredModel(id, id, ModelAccess.AVAILABLE, recommended = false)

    private companion object {
        const val DEADLINE_MS = 5_000L
    }

    /** One held call: the row waits until it is entered, then releases it. Both waits have a deadline. */
    private class Hold(private val deadlineMs: Long = DEADLINE_MS) {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun enter() {
            entered.countDown()
            check(released.await(deadlineMs, TimeUnit.MILLISECONDS)) { "the held call was never released" }
        }

        fun awaitEntered() = check(entered.await(deadlineMs, TimeUnit.MILLISECONDS)) { "the held call was never reached" }

        fun release() = released.countDown()
    }

    /** A discoverer whose calls are held per key, in the order the row armed them, until the row answers. */
    private class GatedDiscoverer : ProviderModelDiscoverer {
        class Gate {
            val hold = Hold()
            @Volatile var answer: ProviderDiscovery = ProviderDiscovery.Listed(emptyList(), 0L)
            fun awaitEntered() = hold.awaitEntered()
            fun release(result: ProviderDiscovery) {
                answer = result
                hold.release()
            }
        }

        private val armed = ConcurrentHashMap<String, ConcurrentLinkedQueue<Gate>>()
        private val all = ConcurrentLinkedQueue<Gate>()

        fun hold(apiKey: String): Gate = Gate().also { gate ->
            armed.getOrPut(apiKey) { ConcurrentLinkedQueue() }.add(gate)
            all.add(gate)
        }

        fun releaseAll() = all.forEach { it.hold.release() }

        override fun discoverModels(provider: Provider, apiKey: String): ProviderDiscovery {
            val gate = checkNotNull(armed[apiKey]?.poll()) { "no held discovery armed for this key" }
            gate.hold.enter()
            return gate.answer
        }
    }

    private class FakeSecrets : SecretStore {
        private val keys = ConcurrentHashMap<Provider, String>()
        override fun put(provider: Provider, secret: String) { keys[provider] = secret }
        override fun get(provider: Provider): String? = keys[provider]
        override fun remove(provider: Provider) { keys.remove(provider) }
        override fun storedProviders(): Set<Provider> = keys.keys.toSet()
    }

    /**
     * SharedPreferences in memory. A commit applies its whole batch at once, as the real one does. A row can
     * hold the NEXT `getAll` or the NEXT commit, and only that one, so no step the row is not about waits.
     */
    private class FakePreferences : SharedPreferences {
        val values = ConcurrentHashMap<String, Any>()
        private var nextRead: Hold? = null
        private var nextCommit: Hold? = null
        private val holds = ConcurrentLinkedQueue<Hold>()

        @Synchronized fun holdNextRead(): Hold = Hold().also { nextRead = it; holds.add(it) }
        @Synchronized fun holdNextCommit(): Hold = Hold().also { nextCommit = it; holds.add(it) }
        fun releaseAll() = holds.forEach { it.release() }

        @Synchronized private fun takeRead(): Hold? = nextRead.also { nextRead = null }
        @Synchronized private fun takeCommit(): Hold? = nextCommit.also { nextCommit = null }

        override fun getAll(): MutableMap<String, *> {
            takeRead()?.enter()
            return HashMap(values)
        }
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any>()
            private val removes = HashSet<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { if (value == null) removes.add(key) else puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { if (values == null) removes.add(key) else puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                takeCommit()?.enter()
                synchronized(this@FakePreferences) {
                    if (clearAll) values.clear()
                    removes.forEach { values.remove(it) }
                    values.putAll(puts)
                }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
