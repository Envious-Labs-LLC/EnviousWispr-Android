package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.cleanup.CleanupLanguage
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.debug.BenchForegroundService
import com.envi.wispr.llama.S1Native
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.processing.ProcessingPreference
import com.envi.wispr.processing.ProcessingQualification
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copy-ahead decoding on the portable runtime (#510).
 *
 * Harness Contract rows (in-process `S1Native` with the DEBUG library's test switches, `s1_jni.cpp`): every
 * speculative branch is proven to have fired from its own counter, and each ends with the one-token loop's text and
 * end metadata on a parity-stable fixture, followed by a correct next request. The fidelity row compares copy-ahead
 * with the one-token loop on 45 synthetic benchmark dictations and fails on any real word added or lost; its oracle
 * is itself checked on planted errors and on the accept-everything mutation. Product Outcome row: a ~500-character
 * take through the real `:polish` binder returns POLISHED (when this fails, a typical long dictation on this phone
 * comes back unpolished).
 *
 * Runner args: `model` = `s1` (default) or `eg1`; `firebaseS1Fixture` / `firebaseEg1Fixture` stage Test Lab files.
 * Not stageable deterministically: EOS arriving inside an accepted guess run (the model decides where it stops);
 * the fidelity report counts natural EOS ends instead.
 */
@RunWith(AndroidJUnit4::class)
class CopyAheadDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = InstrumentationRegistry.getArguments()
    private val model = when (val name = args.getString("model") ?: "s1") {
        "s1" -> LocalPolishModel.S1_MINI
        "eg1" -> LocalPolishModel.EG1_MICRO
        else -> error("model must be s1 or eg1, got $name")
    }
    private val fixture: JSONObject by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        JSONObject(assets.open("copy-ahead-fidelity.json").bufferedReader().use { it.readText() })
    }
    private val cases: List<Pair<String, String>> by lazy {
        val array = fixture.getJSONArray("cases")
        (0 until array.length()).map { array.getJSONObject(it).let { c -> c.getString("id") to c.getString("raw") } }
    }

    private data class Run(val text: String, val end: IntArray, val stats: IntArray, val ms: Long)

    @Test
    fun speculationRunsAndMatchesTheOneTokenLoop() = withModel { load ->
        assertTrue("copy-ahead is not enabled on this load: $load", load.contains("| copy-ahead 4"))
        val (id, raw) = stableCase()
        val copy = generate(raw)
        val plain = generate(raw, guesses = 0)
        assertTrue("$id: no guesses were made", copy.stats[1] > 0)
        assertTrue("$id: no guess was accepted", copy.stats[2] > 0)
        assertEquals("$id: the removal control still guessed", 0, plain.stats[1])
        assertSameEnding(id, plain, copy)
    }

    @Test
    fun aFailedGuessBatchRebuildsAndFinishesLikeTheOneTokenLoop() = withModel {
        val (id, raw) = stableCase()
        val plain = generate(raw, guesses = 0)
        val faulted = generate(raw, fault = 1)
        assertEquals("$id: the injected batch failure was not recovered exactly once", 1, faulted.stats[3])
        assertSameEnding(id, plain, faulted)
        assertNextRequestIsCorrect()
    }

    @Test
    fun aRefusedCacheRemovalRebuildsAndFinishesLikeTheOneTokenLoop() = withModel {
        val (id, raw) = stableCase()
        val plain = generate(raw, guesses = 0)
        val faulted = generate(raw, fault = 2)
        assertTrue("$id: no guess batch ran before the refused removal", faulted.stats[1] > 0)
        assertEquals("$id: the refused removal was not recovered exactly once", 1, faulted.stats[3])
        assertSameEnding(id, plain, faulted)
        assertNextRequestIsCorrect()
    }

    @Test
    fun theTokenAllowanceEndsTheGenerationInsideAcceptedGuesses() = withModel {
        val (id, raw) = stableCase()
        val cap = 6
        val plain = generate(raw, guesses = 0, maxTokens = cap)
        val copy = generate(raw, maxTokens = cap)
        assertTrue("$id: no guess was accepted before the allowance", copy.stats[2] > 0)
        assertEquals("$id: length end code", 1, copy.end[0])
        assertEquals("$id: generated tokens at the allowance", cap, copy.end[1])
        assertEquals("$id: text at the allowance", plain.text, copy.text)
        assertNextRequestIsCorrect()
    }

    @Test
    fun anAbortDuringGenerationEndsAbortedAndTheNextRequestIsClean() = withModel {
        val raw = cases.maxBy { it.second.length }.second
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            S1Native.resetAbort()
            timer.schedule({ S1Native.abort() }, 400, TimeUnit.MILLISECONDS)
            val text = S1Native.generate(systemPrompt(), userPrompt(raw), maxTokens(raw))
            val end = S1Native.lastEnd()
            assertTrue("abort did not end the generation: ${end.toList()}", text.startsWith("ERROR:"))
            assertEquals("aborted end code", 2, end[0])
        } finally {
            timer.shutdownNow()
        }
        assertNextRequestIsCorrect()
    }

    @Test
    fun copyAheadAddsOrLosesNoDictatedWordOnTheFidelitySet() = withModel { load ->
        val rows = JSONArray()
        val real = mutableListOf<String>()
        var mutationFlagged = 0
        var naturalEos = 0
        for ((id, raw) in cases) {
            val plain = generate(raw, guesses = 0)
            val copy = generate(raw)
            val mutated = generate(raw, acceptAll = true)
            val diff = WordDiff.of(raw, plain.text, copy.text)
            if (WordDiff.of(raw, plain.text, mutated.text).changed) mutationFlagged++
            if (copy.end[0] == 0) naturalEos++
            if (diff.real.isNotEmpty()) real += "$id ${diff.real}"
            rows.put(
                JSONObject().put("id", id).put("rawChars", raw.length).put("plainMs", plain.ms).put("copyMs", copy.ms)
                    .put("identical", plain.text == copy.text).put("cosmetic", JSONArray(diff.cosmetic))
                    .put("real", JSONArray(diff.real)).put("end", copy.end[0]).put("guessed", copy.stats[1])
                    .put("accepted", copy.stats[2]).put("plain", plain.text).put("copy", copy.text),
            )
        }
        val first = cases.first()
        val base = generate(first.second, guesses = 0).text
        val words = base.split(' ')
        val dictated = WordDiff.words(first.second).toSet()
        val victim = words.indexOfFirst { word -> WordDiff.words(word).singleOrNull()?.let { it in dictated } == true }
        assertTrue("no dictated word in the first fixture's output to plant a loss on", victim >= 0)
        val dropped = words.filterIndexed { index, _ -> index != victim }.joinToString(" ")
        assertTrue("oracle missed a planted dropped word", WordDiff.of(first.second, base, dropped).real.isNotEmpty())
        assertTrue("oracle missed a planted added word", WordDiff.of(first.second, base, "$base zeppelin").real.isNotEmpty())
        writeReport(
            JSONObject().put("model", model.name).put("load", load).put("cases", rows)
                .put("mutationFlagged", mutationFlagged).put("naturalEos", naturalEos),
        )
        assertTrue("the accept-everything mutation changed no output, so the oracle proves nothing", mutationFlagged > 0)
        assertTrue("copy-ahead added or lost dictated words: $real", real.isEmpty())
    }

    @Test
    fun aFiveHundredCharacterTakeComesBackPolishedThroughTheRealPolishService() {
        stageAndVerify()
        val raw = fixture.getJSONObject("productCase").getString("raw")
        val policy = PolishPolicy.LocalS1(
            S1ControlSettings.DEFAULT, ProcessingPreference.DEFAULT, ProcessingQualification.NONE, model,
        )
        var service: IPolishService? = null
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPolishService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        assertTrue(context.bindService(Intent(context, PolishService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("PolishService did not connect", connected.await(15, TimeUnit.SECONDS))
            val proxy = checkNotNull(service)
            proxy.warmUpWithPolicy(policy)
            proxy.awaitWarm()
            val done = CompletableFuture<PolishOutcome>()
            val started = SystemClock.elapsedRealtime()
            proxy.polishRequestWithCleanupForTake(
                510L, raw, CleanupRequestOptions(CleanupOptions()), policy, "copy-ahead-500",
                object : IPolishCallback.Stub() {
                    override fun onOutcome(outcome: PolishOutcome?) { done.complete(checkNotNull(outcome)) }
                },
            )
            val outcome = done.get(60, TimeUnit.SECONDS)
            val ms = SystemClock.elapsedRealtime() - started
            writeReport(JSONObject().put("model", model.name).put("productCase", outcome.reason.name).put("ms", ms), "product")
            assertEquals("a ${raw.length}-character take came back ${outcome.reason} after ${ms}ms", PolishReason.POLISHED, outcome.reason)
        } finally {
            context.unbindService(connection)
        }
    }

    // --- harness

    private fun IPolishService.awaitWarm() {
        // A real take queues behind the warm-up on the service's single worker; this request is that wait.
        val done = CompletableFuture<PolishOutcome>()
        polishRequestWithCleanupForTake(
            509L, "warm", CleanupRequestOptions(CleanupOptions()), PolishPolicy.LocalS1(
                S1ControlSettings.DEFAULT, ProcessingPreference.DEFAULT, ProcessingQualification.NONE, model,
            ), "copy-ahead-warm",
            object : IPolishCallback.Stub() {
                override fun onOutcome(outcome: PolishOutcome?) { done.complete(checkNotNull(outcome)) }
            },
        )
        done.get(120, TimeUnit.SECONDS)
    }

    private fun stageAndVerify() {
        FirebaseModelFixture.stageS1IfRequested(context)
        FirebaseModelFixture.stageEg1IfRequested(context)
        val descriptor = model.descriptor()
        assertTrue(
            "${descriptor.displayName} is not verified on this device; stage it (firebaseS1Fixture / firebaseEg1Fixture)",
            ModelDeliveryStore(ModelStorage.root(context)).isVerified(descriptor),
        )
    }

    private fun <T> withModel(block: (String) -> T): T {
        stageAndVerify()
        val selection = checkNotNull(S1ModelSelector.resolve(context, model.descriptor()))
        BenchForegroundService.started = CompletableFuture()
        context.startForegroundService(Intent(context, BenchForegroundService::class.java))
        BenchForegroundService.started.get(10, TimeUnit.SECONDS)
        val init = S1Native.initialize(context.applicationInfo.nativeLibraryDir)
        assertFalse(init, init.startsWith("ERROR:"))
        clearSwitches()
        try {
            val load = S1Native.loadModel(selection.file.path, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT)
            assertFalse(load, load.startsWith("ERROR:"))
            val warmSystem = when (model) {
                LocalPolishModel.S1_MINI -> S1Config.SYSTEM_PROMPT
                LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.SYSTEM_PROMPT
            }
            assertTrue("prefix warm failed", S1Native.warmPrefix(warmSystem))
            return block(load)
        } finally {
            clearSwitches()
            S1Native.unload()
            context.stopService(Intent(context, BenchForegroundService::class.java))
        }
    }

    private fun generate(
        raw: String,
        guesses: Int? = null,
        fault: Int = 0,
        acceptAll: Boolean = false,
        maxTokens: Int = maxTokens(raw),
    ): Run {
        clearSwitches()
        guesses?.let { Os.setenv("EW_COPY_AHEAD_GUESSES", it.toString(), true) }
        if (fault != 0) Os.setenv("EW_COPY_AHEAD_FAULT", fault.toString(), true)
        if (acceptAll) Os.setenv("EW_COPY_AHEAD_ACCEPT_ALL", "1", true)
        try {
            S1Native.resetAbort()
            val started = SystemClock.elapsedRealtime()
            val text = S1Native.generate(systemPrompt(), userPrompt(raw), maxTokens)
            val ms = SystemClock.elapsedRealtime() - started
            assertFalse("generation failed: $text", text.startsWith("ERROR:"))
            return Run(text, S1Native.lastEnd(), S1Native.lastCopyAheadStats(), ms)
        } finally {
            clearSwitches()
        }
    }

    private fun clearSwitches() {
        Os.unsetenv("EW_COPY_AHEAD_GUESSES")
        Os.unsetenv("EW_COPY_AHEAD_FAULT")
        Os.unsetenv("EW_COPY_AHEAD_ACCEPT_ALL")
    }

    /** The first case whose copy-ahead text equals the one-token loop's: recovery rows compare against it exactly. */
    private fun stableCase(): Pair<String, String> {
        for (case in cases.take(12)) {
            val plain = generate(case.second, guesses = 0)
            val copy = generate(case.second)
            if (plain.text == copy.text && copy.stats[2] > 0 && plain.end[0] == 0) return case
        }
        error("no parity-stable case among the first 12 fixtures; the fidelity report will say why")
    }

    private fun assertSameEnding(id: String, expected: Run, actual: Run) {
        assertEquals("$id: text", expected.text, actual.text)
        assertEquals("$id: end code", expected.end[0], actual.end[0])
        assertEquals("$id: generated tokens", expected.end[1], actual.end[1])
        assertEquals("$id: prompt tokens", expected.end[2], actual.end[2])
        assertEquals("$id: prefix cache use", expected.end[3], actual.end[3])
    }

    private fun assertNextRequestIsCorrect() {
        val (id, raw) = cases[1]
        val plain = generate(raw, guesses = 0)
        val copy = generate(raw)
        assertEquals("$id: next request recovered a fault", 0, copy.stats[3])
        assertEquals("$id: next request after a fault diverged from the one-token loop", plain.text, copy.text)
    }

    private fun systemPrompt() = when (model) {
        LocalPolishModel.S1_MINI -> S1Config.SYSTEM_PROMPT
        LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.systemPrompt(CleanupLanguage.Unknown)
    }

    private fun userPrompt(raw: String) = when (model) {
        LocalPolishModel.S1_MINI -> S1PromptBuilder.buildUserPrompt(raw, S1ControlSettings.DEFAULT)
        LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.buildUserPrompt(raw)
    }

    private fun maxTokens(raw: String) = when (model) {
        LocalPolishModel.S1_MINI -> S1PromptBuilder.maxOutputTokens(raw)
        LocalPolishModel.EG1_MICRO -> EGOneNamedLanguagePromptBuilder.maxOutputTokens(raw)
    }

    private fun writeReport(report: JSONObject, suffix: String = "fidelity") {
        // Synthetic benchmark fixtures only, never user content. Test Lab pulls shared storage.
        val name = "copy-ahead-${model.name.lowercase()}-$suffix.json"
        File(context.filesDir, "bench").apply { mkdirs() }.resolve(name).writeText(report.toString(1))
        context.getExternalFilesDir("bench")?.resolve(name)?.writeText(report.toString(1))
    }
}

/**
 * The fidelity oracle (#510): words copy-ahead added or lost against the one-token loop. A lost word that the
 * dictation contained, or an added word that neither the dictation nor the one-token loop contained, is REAL; any
 * other difference (a digit for a spoken number, punctuation) is cosmetic and reported.
 */
internal data class WordDiff(val real: List<String>, val cosmetic: List<String>) {
    val changed: Boolean get() = real.isNotEmpty() || cosmetic.isNotEmpty()

    companion object {
        private val numbers = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        ).withIndex().associate { (index, word) -> word to index.toString() }

        fun words(text: String): List<String> = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }.map { numbers[it] ?: it }

        fun of(raw: String, expected: String, actual: String): WordDiff {
            val dictated = words(raw).toSet()
            val remaining = words(actual).groupingBy { it }.eachCount().toMutableMap()
            val lost = mutableListOf<String>()
            for (word in words(expected)) {
                val count = remaining[word] ?: 0
                if (count > 0) remaining[word] = count - 1 else lost += word
            }
            val added = remaining.flatMap { (word, count) -> List(count) { word } }
            val real = lost.filter { it in dictated }.map { "-$it" } + added.filter { it !in dictated }.map { "+$it" }
            val cosmetic = lost.filter { it !in dictated }.map { "-$it" } + added.filter { it in dictated }.map { "+$it" }
            return WordDiff(real, cosmetic)
        }
    }
}
