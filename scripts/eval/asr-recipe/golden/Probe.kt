package com.envi.wispr.asr

import java.io.File
import kotlin.math.max
import kotlin.math.min

// Probe (#453): replays TdtRecipe.transcribe's window loop for one WAV and prints each window's tokens as text, then
// each merge stage, so a lost span can be pinned to the window or to the merge step. Diagnostic only.
fun main(args: Array<String>) {
    val model = TdtModel.open(File(args[0]), File(args[1]).readBytes(), 4)
    val recipe = TdtRecipe(model)
    val samples = readWav(File(args[2]))
    val n = samples.size
    val f = TdtRecipe.FRAME
    val speechEnd = TdtRecipe.speechEnd(samples)
    println("samples=$n seconds=${n / 16000.0} speechEnd=${speechEnd / 16000.0}")
    val outputs = ArrayList<List<TdtRecipe.Token>>()
    var start = 0
    var index = 0
    while (start < n) {
        val warmup = TdtRecipe.lastChunkWarmup(start, n, speechEnd)
        val visible = max(f, TdtRecipe.CHUNK - warmup)
        val isLast = start + visible >= n
        val end = if (isLast) n else start + visible
        val audioEnd = if (isLast) min(end, speechEnd) else end
        val context = if (warmup > 0) 0 else if (index > 0) f else 0
        val contextStart = start - max(warmup, context)
        val segment = samples.copyOfRange(contextStart, audioEnd)
        val toks = if (warmup > 0) recipe.decodeWindow(segment, 0, contextStart, isLast, padToWindow = true, emitAfterFrame = start / f, startFrame = 0)
        else recipe.decodeWindow(segment, context, start, isLast, padToWindow = true)
        println("window $index start=${start / 16000.0}s warmup=${warmup / 16000.0}s contextStart=${contextStart / 16000.0}s audioEnd=${audioEnd / 16000.0}s tokens=${toks.size} frames=${toks.firstOrNull()?.frame}..${toks.lastOrNull()?.frame}")
        println("  text: " + recipe.text(toks))
        if (warmup > 0) {
            val all = recipe.decodeWindow(segment, 0, contextStart, isLast, padToWindow = true, emitAfterFrame = null, startFrame = 0)
            println("  same window, no emit filter: tokens=${all.size} frames=${all.firstOrNull()?.frame}..${all.lastOrNull()?.frame}")
            println("  text: " + recipe.text(all))
            val padded = segment.copyOf(TdtRecipe.WINDOW_SAMPLES)
            println("  segment=${segment.size} padded=${padded.size} window=${TdtRecipe.WINDOW_SAMPLES}")
        }
        outputs += toks
        index++
        if (isLast) break
        start += TdtRecipe.STRIDE
    }
    var merged = outputs.first()
    for (next in outputs.drop(1)) merged = recipe.merge(merged, next)
    println("merged: " + recipe.text(merged))
    merged = recipe.monotonic(merged)
    println("monotonic: " + recipe.text(merged))
    if (outputs.size > 1) merged = recipe.collapseSeamDuplicates(merged)
    println("collapsed: " + recipe.text(merged))
    if (outputs.size > 1 && merged.size > 1) merged = recipe.repairSeamGaps(samples, merged)
    println("repaired: " + recipe.text(merged))
    model.close()
}
