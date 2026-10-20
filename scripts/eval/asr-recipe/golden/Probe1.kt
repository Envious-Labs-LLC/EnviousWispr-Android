package com.envi.wispr.asr

import java.io.File

// Probe1 (#453): a single-window take step by step: encoder valid frames, then every decoder step's token and
// duration for the first steps, to see why a take decodes to nothing. Diagnostic only.
fun main(args: Array<String>) {
    val model = TdtModel.open(File(args[0]), File(args[1]).readBytes(), 4)
    val samples = readWav(File(args[2]))
    val n = samples.size
    val f = TdtRecipe.FRAME
    val aligned = if (n % f == 0) samples else samples.copyOf(n + f - n % f)
    val padded = aligned.copyOf(TdtRecipe.WINDOW_SAMPLES)
    model.encode(padded, aligned.size).use { enc ->
        println("samples=$n aligned=${aligned.size} encoderFrames=${enc.frames} expected=${aligned.size / f}")
        var state = model.initialState()
        var prev = model.blankId
        var t = 0
        var steps = 0
        while (t < enc.frames && steps < 400) {
            val s = model.step(enc, t, prev, state)
            val d = TdtRecipe.DURATION_BINS[s.durationBin]
            if (steps < 40 || s.token != model.blankId) println("t=$t token=${if (s.token == model.blankId) "<blank>" else model.vocab[s.token]} dur=$d")
            if (s.token != model.blankId) { prev = s.token; state = s.next }
            t += if (d == 0) 1 else d
            steps++
        }
        println("steps=$steps endT=$t")
    }
    model.close()
}
