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
        cacheDir().listFiles()?.forEach { entry -> assertTrue("cache entry is a file", entry.isFile); entry.delete() }
        assertEquals("cache folder emptied", 0, cacheDir().listFiles()?.size ?: 0)
    }

    private fun log(file: String, line: String) {
        File(out, file).appendText(line + "\n")
        android.util.Log.i("MnnNativeDeviceTest", line)
    }

    private fun load(): Long {
        val started = System.nanoTime()
        val error = MnnNative.loadModel("$dir/model/config.json", "cpu", 4, cacheDir().path)
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

    /** An abort from another thread stops generation before the next token (plan §3.1, P6). */
    @Test fun abortStopsGenerationBetweenTokens() {
        assertTrue("model and fixtures not staged in $dir", staged())
        val system = File("$dir/system.txt").readText()
        val longest = File("$dir/cases.jsonl").readLines().filter { it.isNotBlank() }
            .map { JSONObject(it) }.maxBy { it.getString("input").length }
        load()
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
