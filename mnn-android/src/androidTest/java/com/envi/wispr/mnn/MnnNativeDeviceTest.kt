package com.envi.wispr.mnn

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Chunk 1 engine proof for #531 (Product Outcome at the polish boundary: the shipped model on the real runtime).
 * When this fails, the user sees EG-1 Micro produce no polish, wrong polish, or polish that cannot be stopped at the
 * deadline. Needs the MNN model folder and fixtures staged on the device (instrumentation arg `mnndir`, default
 * `/data/local/tmp/eg1mnn`: `model/` with the five model files, `cases.jsonl`, `system.txt`). Every run writes its
 * timings and memory (peak resident, private, file-backed: `polish-engines.md` RULE: every-model-run-reports-memory)
 * to the app's external files folder and logcat tag `MnnNativeDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
class MnnNativeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = InstrumentationRegistry.getArguments().getString("mnndir", "/data/local/tmp/eg1mnn")
    private val out = File(context.getExternalFilesDir(null), "mnn-chunk1").apply { mkdirs() }

    private fun staged(): Boolean = File("$dir/model/config.json").isFile && File("$dir/cases.jsonl").isFile

    private fun memory(): String {
        val fields = File("/proc/self/status").readLines().associate { line ->
            line.substringBefore(':') to line.substringAfter(':').trim()
        }
        return listOf("VmHWM", "VmRSS", "RssAnon", "RssFile").joinToString(" ") { "$it=${fields[it]}" }
    }

    private fun cacheDir(): File = File(context.noBackupFilesDir, "mnn-test-cache").apply { mkdirs() }

    /** Empties the test's own cache folder entry by entry (no recursive delete) and asserts it is empty. */
    private fun clearCache() {
        cacheDir().listFiles()?.filter { it.name != "prefix" }?.forEach { entry -> assertTrue("cache entry is a file", entry.isFile); entry.delete() }
        assertEquals("cache folder emptied", 0, cacheDir().listFiles()?.count { it.name != "prefix" } ?: 0)
    }

    private fun log(file: String, line: String) {
        File(out, file).appendText(line + "\n")
        android.util.Log.i("MnnNativeDeviceTest", line)
    }

    private fun prefixDir(): File = File(cacheDir(), "prefix").apply { mkdirs() }

    private fun clearPrefix() {
        prefixDir().listFiles()?.forEach { entry -> assertTrue("prefix entry is a file", entry.isFile); entry.delete() }
        assertEquals("prefix folder emptied", 0, prefixDir().listFiles()?.size ?: 0)
    }

    private fun openFiles(): Int = File("/proc/self/fd").list()?.size ?: -1

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    /** Shared words over the longer answer's word count, counting repeats: 1.0 is the same words in any order. */
    private fun overlap(a: String, b: String): Double {
        val left = words(a).groupingBy { it }.eachCount()
        val right = words(b).groupingBy { it }.eachCount()
        val shared = left.entries.sumOf { (w, n) -> minOf(n, right[w] ?: 0) }
        return shared.toDouble() / maxOf(left.values.sum(), right.values.sum(), 1)
    }

    private fun load(prefix: Boolean = false): Long {
        val started = System.nanoTime()
        val error = MnnNative.loadModel("$dir/model/config.json", "cpu", 4, cacheDir().path, if (prefix) prefixDir().path else "")
        assertEquals("load error", "", error)
        return (System.nanoTime() - started) / 1_000_000
    }

    /**
     * The step loop must match MNN's own generation after the trim `PolishService` applies to every output
     * (`PolishService.polishWithS1`), and thinking must stay off (plan §3.1, P8). Exact byte matches are counted too.
     */
    @Test fun stepLoopMatchesReferenceOnEveryFixture() {
        assertTrue("model and fixtures not staged in $dir", staged())
        val system = File("$dir/system.txt").readText()
        File(out, "equivalence.jsonl").delete()
        log("equivalence.jsonl", "{\"device\":\"${Build.MODEL}\",\"soc\":\"${Build.SOC_MODEL}\",\"start\":\"${memory()}\"}")
        clearCache()
        val firstLoadMs = load()
        log("equivalence.jsonl", "{\"first_load_ms_cache_build\":$firstLoadMs,\"mem\":\"${memory()}\"}")
        var mismatches = 0
        var exact = 0
        File("$dir/cases.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
            val case = JSONObject(line)
            val cap = case.optInt("cap", 512)
            MnnNative.resetAbort()
            val stepped = MnnNative.generate(system, case.getString("input"), cap, 2048)
            val end = MnnNative.lastEnd()
            val reference = MnnNative.referenceResponse(system, case.getString("input"), cap)
            if (stepped.trim() != reference.trim()) mismatches++
            if (stepped == reference) exact++
            assertFalse("thinking leaked in ${case.getString("id")}", stepped.contains("<think>"))
            assertFalse("error in ${case.getString("id")}: $stepped", stepped.startsWith("ERROR:"))
            log(
                "equivalence.jsonl",
                JSONObject().put("id", case.getString("id")).put("end", end[0]).put("gen", end[1]).put("prompt", end[2])
                    .put("prefill_ms", end[3]).put("decode_ms", end[4]).put("same_as_reference", stepped == reference)
                    .put("output", stepped).apply { if (stepped != reference) put("reference", reference) }.toString(),
            )
        }
        log("equivalence.jsonl", "{\"end_mem\":\"${memory()}\",\"trimmed_mismatches\":$mismatches,\"exact_matches\":$exact}")
        MnnNative.unload()
        val secondLoadMs = load()
        log("equivalence.jsonl", "{\"second_load_ms_cache_reused\":$secondLoadMs,\"mem\":\"${memory()}\"}")
        MnnNative.unload()
        assertEquals("step loop differs from MNN's own generation", 0, mismatches)
    }

    /**
     * Prefix reuse (plan §3.6): the split prefill drifts numerically, so greedy text can take another valid path (seen on
     * LONG-01: "Third, the budget" became a third list item). Every changed answer is recorded and must keep at least
     * 90% of the plain answer's words; quality itself is judged by the graded exam, not here. open files stay flat across many requests (the MNN handle leak this app patches), at most four
     * saved entries, and a damaged or unpublished entry is rebuilt rather than trusted.
     */
    @Test fun prefixReuseKeepsTheWordsHoldsOpenFilesFlatAndRebuildsDamagedEntries() {
        assertTrue("model and fixtures not staged in $dir", staged())
        val system = File("$dir/system.txt").readText()
        val cases = File("$dir/cases.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        File(out, "prefix.jsonl").delete()
        load(prefix = false)
        val plain = cases.associate { c -> MnnNative.resetAbort(); c.getString("id") to MnnNative.generate(system, c.getString("input"), c.optInt("cap", 512), 2048) }
        MnnNative.unload()

        clearPrefix()
        load(prefix = true)
        assertTrue("warm-up built the entry", MnnNative.warmPrefix(system))
        val firstPass = cases.associate { c -> MnnNative.resetAbort(); c.getString("id") to MnnNative.generate(system, c.getString("input"), c.optInt("cap", 512), 2048) }
        val filesAfterFirst = openFiles()
        val secondPass = cases.associate { c -> MnnNative.resetAbort(); c.getString("id") to MnnNative.generate(system, c.getString("input"), c.optInt("cap", 512), 2048) }
        val filesAfterSecond = openFiles()
        var wordChanges = 0
        var punctuationOnly = 0
        var lowestOverlap = 1.0
        cases.forEach { c ->
            val id = c.getString("id")
            assertFalse("error in $id: ${secondPass[id]}", secondPass.getValue(id).startsWith("ERROR:"))
            assertEquals("the second pass over a saved entry matches the first, $id", firstPass[id], secondPass[id])
            if (words(plain.getValue(id)) != words(secondPass.getValue(id))) {
                wordChanges++
                lowestOverlap = minOf(lowestOverlap, overlap(plain.getValue(id), secondPass.getValue(id)))
                log("prefix.jsonl", JSONObject().put("word_change", id).put("plain", plain[id]).put("prefix", secondPass[id]).toString())
            }
            else if (plain[id] != secondPass[id]) punctuationOnly++
        }
        val entries = prefixDir().listFiles().orEmpty().count { it.name.endsWith(".ok") }
        log("prefix.jsonl", "{\"lowest_overlap\":$lowestOverlap,\"word_changes\":$wordChanges,\"punctuation_only\":$punctuationOnly,\"open_files_after_first\":$filesAfterFirst,\"open_files_after_second\":$filesAfterSecond,\"entries\":$entries,\"prefix_bytes\":${prefixDir().listFiles().orEmpty().sumOf { it.length() }}}")
        assertTrue("prefix reuse kept only ${"%.2f".format(lowestOverlap)} of an answer's words", lowestOverlap >= 0.9)
        assertTrue("open files grew across ${cases.size} requests: $filesAfterFirst -> $filesAfterSecond", filesAfterSecond - filesAfterFirst <= 2)
        assertTrue("at most four saved entries, found $entries", entries in 1..4)

        // A truncated entry file and a missing sidecar are both rebuilt, and the answer stays the same.
        val probe = cases.first()
        val expected = secondPass.getValue(probe.getString("id"))
        val dataFile = prefixDir().listFiles().orEmpty().first { it.name.endsWith("_0.v") || it.name.endsWith("_0.k") }
        dataFile.writeBytes(dataFile.readBytes().copyOf(maxOf(1, (dataFile.length() / 2).toInt())))
        MnnNative.resetAbort()
        assertEquals("a truncated entry is rebuilt", expected, MnnNative.generate(system, probe.getString("input"), probe.optInt("cap", 512), 2048))
        prefixDir().listFiles().orEmpty().filter { it.name.endsWith(".ok") }.forEach { it.delete() }
        MnnNative.resetAbort()
        assertEquals("an unpublished entry is rebuilt", expected, MnnNative.generate(system, probe.getString("input"), probe.optInt("cap", 512), 2048))
        assertTrue("the rebuilt entry is published again", prefixDir().listFiles().orEmpty().any { it.name.endsWith(".ok") })

        // A sidecar cut short is not trusted: the entry is rebuilt and published again.
        val sidecar = prefixDir().listFiles().orEmpty().first { it.name.endsWith(".ok") }
        sidecar.writeText(sidecar.readLines().take(2).joinToString("\n"))
        MnnNative.resetAbort()
        assertEquals("a partial sidecar is rebuilt", expected, MnnNative.generate(system, probe.getString("input"), probe.optInt("cap", 512), 2048))
        assertTrue("the rebuilt sidecar is complete again", sidecar.readLines().size > 2)

        // An abort raised before the request runs stops it before either prefill.
        MnnNative.abort()
        assertEquals("ERROR: aborted", MnnNative.generate(system, probe.getString("input"), 64, 2048))
        assertEquals("end code", 2, MnnNative.lastEnd()[0])

        // An abort landing between the two prefills stops the request there, and the next request is unaffected.
        MnnNative.resetAbort()
        MnnNative.testAbortAfterPrefixCall()
        assertEquals("ERROR: aborted", MnnNative.generate(system, probe.getString("input"), 64, 2048))
        assertEquals("end code", 2, MnnNative.lastEnd()[0])
        MnnNative.resetAbort()
        assertEquals("the request after an abort is whole", expected, MnnNative.generate(system, probe.getString("input"), probe.optInt("cap", 512), 2048))
        MnnNative.unload()
    }

    /** Five distinct prefixes keep only four whole entries: the oldest goes entirely, the newest stays (plan §3.6). */
    @Test fun aFifthPrefixEvictsTheOldestWholeEntryAndKeepsTheNewest() {
        assertTrue("model and fixtures not staged in $dir", staged())
        val base = File("$dir/system.txt").readText()
        clearPrefix()
        load(prefix = true)
        val keysInOrder = mutableListOf<String>()
        for (variant in 1..5) {
            MnnNative.resetAbort()
            val out = MnnNative.generate("$base Variant $variant.", "<TRANSCRIPT>\nok see you at five\n</TRANSCRIPT>", 32, 2048)
            assertFalse("variant $variant failed: $out", out.startsWith("ERROR:"))
            val sidecars = prefixDir().listFiles().orEmpty().filter { it.name.endsWith(".ok") }.map { it.name.removeSuffix(".ok") }
            val newest = sidecars.first { it !in keysInOrder }
            keysInOrder += newest
            // Entries are ordered by sidecar time; stamp each a second apart instead of waiting for the clock.
            File(prefixDir(), "$newest.ok").setLastModified(1_700_000_000_000L + variant * 1_000L)
        }
        val left = prefixDir().listFiles().orEmpty().filter { it.name.endsWith(".ok") }.map { it.name.removeSuffix(".ok") }.toSet()
        File(out, "eviction.txt").writeText("order=$keysInOrder left=$left\n")
        MnnNative.unload()
        assertEquals("four whole entries kept", 4, left.size)
        assertFalse("the oldest entry was evicted", keysInOrder.first() in left)
        assertTrue("the newest entry stays", keysInOrder.last() in left)
        assertTrue("nothing of the evicted entry remains", prefixDir().listFiles().orEmpty().none { it.name.startsWith(keysInOrder.first()) })
    }

    /** An abort from another thread stops generation before the next token, with prefix reuse on (plan §3.1, P6). */
    @Test fun abortStopsGenerationBetweenTokens() {
        assertTrue("model and fixtures not staged in $dir", staged())
        val system = File("$dir/system.txt").readText()
        val longest = File("$dir/cases.jsonl").readLines().filter { it.isNotBlank() }
            .map { JSONObject(it) }.maxBy { it.getString("input").length }
        load(prefix = true)
        MnnNative.resetAbort()
        val abortedAt = AtomicLong(0)
        val aborter = thread {
            Thread.sleep(1_500)
            abortedAt.set(System.nanoTime())
            MnnNative.abort()
        }
        val result = MnnNative.generate(system, longest.getString("input"), 1024, 2048)
        val returnedAt = System.nanoTime()
        aborter.join()
        val end = MnnNative.lastEnd()
        File(out, "abort.txt").writeText("end=${end.toList()} lag_ms=${(returnedAt - abortedAt.get()) / 1_000_000}\n")
        MnnNative.unload()
        assertEquals("ERROR: aborted", result)
        assertEquals("end code", 2, end[0])
        assertTrue("abort took effect before it was requested", abortedAt.get() in 1 until returnedAt)
    }
}
