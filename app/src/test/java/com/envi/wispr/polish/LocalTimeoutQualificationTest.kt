package com.envi.wispr.polish

import android.content.SharedPreferences
import com.envi.wispr.processing.ProcessingBackend
import com.envi.wispr.processing.ProcessingEvidenceStamp
import com.envi.wispr.processing.ProcessingObservation
import com.envi.wispr.processing.ProcessingPreference
import com.envi.wispr.processing.ProcessingQualification
import com.envi.wispr.processing.ProcessingUsage
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.providers.ProviderKeyCheck
import com.envi.wispr.providers.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome (#559): a local polish timeout falls back for that one take and never locks a Custom priority
 * out of local polish. When this fails, the user sees every later dictation come back unpolished
 * (`LOCAL_NOT_READY`), across restarts, after a single slow generation.
 *
 * The rig is the real repository over an in-memory preference file (a new repository instance is a restart), the
 * engine's own timeout producer [localTimeoutUsage], and the real [S1LoadedConfiguration] that decides whether the
 * next take may load the model. Only the `:polish` service around them is absent.
 */
class LocalTimeoutQualificationTest {
    private val context = "a".repeat(64)
    private val sha = "b".repeat(64)
    private val gpu = ProcessingBackend.GPU
    private val cpu = ProcessingBackend.CPU
    private val geniex = S1RuntimeKind.GENIEX.backends
    private val custom = ProcessingPreference.DEFAULT.custom(listOf(gpu, cpu))
    private val prefs = MemoryPreferences()

    private fun repository() = ProviderConfigurationRepository(prefs, NoSecrets) { _, _ -> ProviderKeyCheck.Accepted }
    private fun stamp(n: Long) = ProcessingEvidenceStamp(1, n)
    private fun observation(id: Long, reason: PolishReason, usage: ProcessingUsage) =
        ProcessingObservation("take-$id", id, id, "local", reason, custom, usage, id)

    /** What the next take's warm-up does with the saved evidence: the routes it may load, or the refusal. */
    private fun nextTakeLoads(preference: ProcessingPreference): Result<ProcessingBackend> {
        val saved = repository()
        val next = S1LoadConfiguration(context, geniex, preference, saved.loadS1Qualification(), artifactStamp = "installed")
        return runCatching { S1LoadedConfiguration().ensure(next) { candidates -> candidates.first() }.backend }
    }

    private fun stageQualifiedCustomPriority() {
        repository().setS1Processing(custom)
        repository().setS1Qualification(ProcessingQualification.of(context, setOf(gpu, cpu)))
        // The staged precondition landed: both routes qualified and the next take loads the preferred one.
        assertEquals(Result.success(gpu), nextTakeLoads(custom))
    }

    @Test fun aLocalTimeoutLeavesACustomPriorityAbleToLoadOnTheNextTakeAndAfterARestart() {
        stageQualifiedCustomPriority()
        val requested = S1LoadConfiguration(context, geniex, custom, repository().loadS1Qualification(), artifactStamp = "installed")
        val policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT, custom, requested.qualification, LocalPolishModel.S1_MINI)
        // `expireLocal` stamps the engine's timeout fact when the deadline wins; the take is recorded after delivery.
        val timedOut = localTimeoutUsage(gpu, requested, policy, sha).copy(generationStamp = stamp(200))
        repository().recordProcessing(observation(1, PolishReason.LOCAL_TIMEOUT, timedOut))

        assertEquals("the timeout disqualified a route", setOf(gpu, cpu), repository().loadS1Qualification().backends)
        assertEquals("the next take after a timeout could not load local polish", Result.success(gpu), nextTakeLoads(custom))
    }

    @Test fun aRealRuntimeFailureStillTakesEveryStandardRouteOutOfACustomPriority() {
        stageQualifiedCustomPriority()
        // The load observation's own producer: the runtime could not initialise, so no generation ran.
        val failedLoad = ProcessingUsage(null, context, custom, false, "", runtimeFailed = true, modelId = LocalPolishModel.S1_MINI.wire,
            artifactStamp = "installed", artifactSha256 = sha, loadStamp = stamp(200))
        repository().recordProcessing(observation(1, PolishReason.LOCAL_NOT_READY, failedLoad))

        assertEquals(emptySet<ProcessingBackend>(), repository().loadS1Qualification().backends)
        val refused = nextTakeLoads(custom).exceptionOrNull()
        assertTrue("a runtime that cannot start was loaded again: $refused", refused is IllegalArgumentException)
        assertEquals("No qualified processing path", refused?.message)
    }

    @Test fun evidenceAnEarlierTimeoutPoisonedRecoversThroughRetryWithoutLosingTheCustomOrder() {
        repository().setS1Processing(custom)
        // Byte-for-byte what a timeout wrote before #559 over a GPU+CPU qualification at retry revision 0.
        prefs.values["s1_processing_qualification"] = "$context;;cpu:0,gpu:0;cpu=1:200,gpu=1:200"
        val locked = nextTakeLoads(custom).exceptionOrNull()
        assertEquals("the poisoned record was not staged", "No qualified processing path", locked?.message)

        // "Retry preferred option next time" is a saved preference write; it removes no evidence and no setting.
        val retried = custom.retry()
        repository().setS1Processing(retried)
        assertEquals(retried, repository().loadS1Processing())
        assertEquals(listOf(gpu, cpu, ProcessingBackend.NPU), repository().loadS1Processing().customOrder?.backends)
        assertEquals("retry did not reopen the proven routes", Result.success(gpu), nextTakeLoads(retried))

        val polished = ProcessingUsage(gpu, context, retried, true, "", modelId = LocalPolishModel.S1_MINI.wire,
            artifactStamp = "installed", artifactSha256 = sha, generationStamp = stamp(300))
        repository().recordProcessing(observation(2, PolishReason.POLISHED, polished))
        assertEquals(setOf(gpu), repository().loadS1Qualification().backends)
        assertEquals("the first polished take after retry did not keep local polish loadable", Result.success(gpu), nextTakeLoads(retried))
    }

    private object NoSecrets : SecretStore {
        override fun put(provider: Provider, secret: String) = error("no key is written by these rows")
        override fun get(provider: Provider): String? = null
        override fun remove(provider: Provider) = Unit
        override fun storedProviders(): Set<Provider> = emptySet()
    }

    /** A preference file in memory; a commit applies its whole batch at once, as the real one does. */
    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = synchronized(this) { HashMap(values) }
        override fun getString(key: String, defValue: String?): String? = synchronized(this) { values[key] as? String ?: defValue }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(this) { values[key] as? MutableSet<String> ?: defValues }
        override fun getInt(key: String, defValue: Int): Int = synchronized(this) { values[key] as? Int ?: defValue }
        override fun getLong(key: String, defValue: Long): Long = synchronized(this) { values[key] as? Long ?: defValue }
        override fun getFloat(key: String, defValue: Float): Float = synchronized(this) { values[key] as? Float ?: defValue }
        override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(this) { values[key] as? Boolean ?: defValue }
        override fun contains(key: String): Boolean = synchronized(this) { values.containsKey(key) }
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
                synchronized(this@MemoryPreferences) {
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
