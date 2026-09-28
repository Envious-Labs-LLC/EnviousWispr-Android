package com.envi.wispr.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Golden bench (#374): the app's exact TdtRecipe + TdtModel sources on the desktop ORT 1.30 build, over the same sets the
// Python reference port was scored on. Output: one JSON line per clip {kind,name,text}.
fun readWav(f: File): FloatArray {
    val b = f.readBytes()
    val n = (b.size - 44) / 2
    val bb = ByteBuffer.wrap(b, 44, n * 2).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(n) { bb.short / 32768f }
}

fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

fun main(args: Array<String>) {
    val modelDir = File(args[0]); val preprocessor = File(args[1]).readBytes(); val listFile = File(args[2]); val out = File(args[3])
    val model = TdtModel.open(modelDir, preprocessor, 4)
    val recipe = TdtRecipe(model)
    out.bufferedWriter().use { w ->
        for (line in listFile.readLines().filter { it.isNotBlank() }) {
            val (kind, name, path, lastSeconds) = (line.split("\t") + listOf("", "", "", "")).take(4)
            var samples = readWav(File(path))
            if (lastSeconds.isNotEmpty()) { val k = (lastSeconds.toDouble() * 16000).toInt(); samples = samples.copyOfRange(samples.size - k, samples.size) }
            val t0 = System.nanoTime()
            val r = recipe.transcribe(samples)
            val ms = (System.nanoTime() - t0) / 1_000_000
            w.write("{\"kind\":\"$kind\",\"name\":\"${esc(name)}\",\"ms\":$ms,\"windows\":${r.windows},\"repairs\":${r.repairs},\"text\":\"${esc(r.text)}\"}\n")
            w.flush()
        }
    }
    model.close()
}
