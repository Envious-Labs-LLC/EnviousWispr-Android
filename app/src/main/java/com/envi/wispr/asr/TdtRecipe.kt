package com.envi.wispr.asr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The macOS app's default batch Parakeet recipe, ported (#374): FluidAudio fork `b29591ad`,
 * `AsrManager(ASRConfig(melChunkContext: true))`. Line references are to that fork:
 * `ChunkProcessor.swift` (chunk loop :505-635, `transcribeChunk` :755-790, `mergeChunks` :952-1219,
 * `enforceMonotonicTimestamps` / `collapseSeamWordDuplicates` :820-938, `repairSeamGaps` :1394-1514) and
 * `TdtDecoderV3.swift` (duration rules :300-326, emission :408-462, last-chunk finalisation :472-598).
 *
 * Pure Kotlin: every model call goes through [TdtRunner], so tests drive this with a fake. The Python reference this
 * was ported from, and the oracles it is checked against, live under `scripts/eval/asr-recipe/`.
 */
internal class TdtRecipe(private val runner: TdtRunner) {

    /** One emitted token: its id, its GLOBAL encoder frame (80 ms each), and its duration in frames. */
    internal data class Token(val id: Int, val frame: Int, val duration: Int)

    /** What one transcription did, for the decode log line. Counts only: never words. */
    internal data class Result(val text: String, val windows: Int, val repairs: Int)

    private val vocab = runner.vocab
    private val blank = runner.blankId
    private val spliceSafe: Set<Int> = vocab.indices.filterTo(HashSet()) { vocab[it].isNotEmpty() && (isBoundary(vocab[it]) || isPunctuationOrSymbol(vocab[it])) }
    private val caseVariant: Map<Int, Int> = buildMap {
        vocab.indices.groupBy { vocab[it].lowercase() }.forEach { (folded, ids) ->
            if (ids.size > 1) {
                val canonical = ids.firstOrNull { vocab[it] == folded } ?: ids.min()
                ids.forEach { put(it, canonical) }
            }
        }
    }

    /** Transcribe a whole take of 16 kHz mono samples. */
    fun transcribe(samples: FloatArray): Result {
        val n = samples.size
        if (n < MIN_SAMPLES) return Result("", 0, 0)
        if (n <= WINDOW_SAMPLES) {
            val aligned = if (n % FRAME == 0) samples else samples.copyOf(n + FRAME - n % FRAME)
            return Result(text(decodeWindow(aligned, context = 0, offsetSample = 0, isLast = true)), 1, 0)
        }
        val speechEnd = speechEnd(samples)
        val outputs = ArrayList<List<Token>>()
        var start = 0
        var index = 0
        while (start < n) {
            val warmup = lastChunkWarmup(start, n, speechEnd)
            val visible = max(FRAME, CHUNK - warmup)
            val isLast = start + visible >= n
            val end = if (isLast) n else start + visible
            val audioEnd = if (isLast) min(end, speechEnd) else end
            if (end <= start || audioEnd <= start) break
            val context = if (warmup > 0) 0 else if (index > 0) FRAME else 0
            val contextStart = start - max(warmup, context)
            val segment = samples.copyOfRange(contextStart, audioEnd)
            outputs += if (warmup > 0) {
                // Final chunk backfilled with real audio: decode from frame 0, emit only from `start` (ChunkProcessor :528-565).
                decodeWindow(segment, 0, contextStart, isLast, emitAfterFrame = start / FRAME, startFrame = 0)
            } else {
                decodeWindow(segment, context, start, isLast)
            }
            index++
            if (isLast) break
            start += STRIDE
        }
        var merged = outputs.first()
        for (next in outputs.drop(1)) merged = merge(merged, next)
        merged = monotonic(merged)
        if (outputs.size > 1) merged = collapseSeamDuplicates(merged)
        var repairs = 0
        if (outputs.size > 1 && merged.size > 1) {
            val repaired = repairSeamGaps(samples, merged)
            repairs = repaired.size - merged.size
            merged = repaired
        }
        return Result(text(merged), outputs.size, repairs)
    }

    /**
     * One window through the greedy TDT loop with a FRESH decoder state. [samples] includes any [context] prefix;
     * timestamps are local frames plus [offsetSample] / [FRAME]. Only frames of real audio are decoded.
     */
    internal fun decodeWindow(
        samples: FloatArray,
        context: Int,
        offsetSample: Int,
        isLast: Boolean,
        emitAfterFrame: Int? = null,
        startFrame: Int? = null,
    ): List<Token> {
        val n = samples.size
        if (n == 0) return emptyList()
        val padded = if (n >= WINDOW_SAMPLES) samples else samples.copyOf(WINDOW_SAMPLES)
        runner.encode(padded, n).use { encoded ->
            val actualFrames = ceilDiv(n - context, FRAME)
            val frames = min(encoded.frames, actualFrames)
            if (frames <= 1) return emptyList()
            val offset = offsetSample / FRAME
            val out = ArrayList<Token>()
            val history = ArrayList<Int>()
            var state = runner.initialState()
            var t = startFrame ?: (context / FRAME)
            var lastEmit = -1
            var atFrame = 0
            var count = 0
            while (t < frames) {
                val step = runner.step(encoded, t, history.lastOrNull() ?: blank, state)
                var duration = DURATION_BINS[step.durationBin]
                if (step.token != blank && duration == 0 && t == lastEmit && atFrame >= 1) duration = 1
                if (step.token == blank && duration == 0) duration = 1
                val current = t
                t += duration
                if (t >= frames) {
                    // TdtDecoderV3 :409 drops a non-blank whose duration leaves the real frames. On the last window
                    // that is the end of the take, and the dropped token is the last piece of the final word: with
                    // the phone's SmoothQuant model it cut "intended" to "intend" and "Friday" to "Frida" on 9 of 26
                    // clips (#421). The last window keeps it; earlier windows still drop it, because the next
                    // window's overlap owns those frames.
                    if (isLast && step.token != blank && current < frames && ++count <= MAX_TOKENS_PER_WINDOW) {
                        val global = current + offset
                        if (emitAfterFrame == null || global >= emitAfterFrame) out += Token(step.token, global, duration)
                        history += step.token
                        state = step.next
                    }
                    break
                }
                if (step.token == blank) continue
                if (++count > MAX_TOKENS_PER_WINDOW) break
                val global = current + offset
                if (emitAfterFrame == null || global >= emitAfterFrame) out += Token(step.token, global, duration)
                history += step.token
                state = step.next
                atFrame = if (current == lastEmit) atFrame + 1 else 1
                lastEmit = current
                if (atFrame >= MAX_SYMBOLS_PER_STEP) { t = current + 1; atFrame = 0 }
            }
            if (isLast) {
                // Punctuation-only finalisation (TdtDecoderV3 :472-598, fork carry #1792).
                var blanks = 0
                var index = min(t, frames - 1)
                val rotation = intArrayOf(index, frames - 1, max(0, frames - 2))
                for (k in 0 until MAX_SYMBOLS_PER_STEP) {
                    if (blanks >= CONSECUTIVE_BLANK_LIMIT) break
                    val step = runner.step(encoded, rotation[k % 3], history.lastOrNull() ?: blank, state)
                    if (step.token == blank || !isPunctuationPiece(vocab[step.token])) {
                        blanks++
                    } else {
                        val global = min(index, frames - 1) + offset
                        if (emitAfterFrame == null || global >= emitAfterFrame) out += Token(step.token, global, 1)
                        history += step.token
                        state = step.next
                    }
                    index += max(1, DURATION_BINS[step.durationBin])
                    rotation[0] = min(index, frames - 1)
                }
            }
            return out
        }
    }

    // ---- merge (ChunkProcessor.mergeChunks :952-1219)

    internal fun merge(left: List<Token>, right: List<Token>): List<Token> {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val leftEnd = left.last().frame * SECONDS_PER_FRAME + SECONDS_PER_FRAME
        val rightStart = right.first().frame * SECONDS_PER_FRAME
        if (leftEnd <= rightStart) return left + right
        val overlapLeft = left.indices.filter { left[it].frame * SECONDS_PER_FRAME + SECONDS_PER_FRAME > rightStart - OVERLAP_SECONDS }
        val overlapRight = right.indices.filter { right[it].frame * SECONDS_PER_FRAME < leftEnd + OVERLAP_SECONDS }
        if (overlapLeft.size < 2 || overlapRight.size < 2) return midpoint(left, right, leftEnd, rightStart)
        fun matches(p: Int, q: Int): Boolean {
            val a = left[overlapLeft[p]]
            val b = right[overlapRight[q]]
            return idsMatch(a.id, b.id) && abs(a.frame - b.frame) * SECONDS_PER_FRAME < OVERLAP_SECONDS / 2
        }
        var best = emptyList<Pair<Int, Int>>()
        for (p in overlapLeft.indices) for (q in overlapRight.indices) {
            if (!matches(p, q)) continue
            val current = ArrayList<Pair<Int, Int>>()
            var k = p
            var l = q
            while (k < overlapLeft.size && l < overlapRight.size && matches(k, l)) { current += k to l; k++; l++ }
            if (current.size > best.size) best = current
        }
        if (best.size >= max(overlapLeft.size / 2, 1)) return useMatches(best, overlapLeft, overlapRight, left, right)
        val rows = overlapLeft.size
        val cols = overlapRight.size
        val dp = Array(rows + 1) { IntArray(cols + 1) }
        for (i in 1..rows) for (j in 1..cols) {
            dp[i][j] = if (matches(i - 1, j - 1)) dp[i - 1][j - 1] + 1 else max(dp[i - 1][j], dp[i][j - 1])
        }
        val lcs = ArrayList<Pair<Int, Int>>()
        var i = rows
        var j = cols
        while (i > 0 && j > 0) {
            when {
                matches(i - 1, j - 1) -> { lcs += (i - 1) to (j - 1); i--; j-- }
                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }
        lcs.reverse()
        if (lcs.isEmpty()) return midpoint(left, right, leftEnd, rightStart)
        return useMatches(lcs, overlapLeft, overlapRight, left, right)
    }

    private fun useMatches(
        matches: List<Pair<Int, Int>>,
        overlapLeft: List<Int>,
        overlapRight: List<Int>,
        left: List<Token>,
        right: List<Token>,
    ): List<Token> {
        val leftIndices = matches.map { overlapLeft[it.first] }
        val rightIndices = matches.map { overlapRight[it.second] }
        val result = ArrayList<Token>()
        if (leftIndices.first() > 0) result += left.subList(0, leftIndices.first())
        for (x in matches.indices) {
            result += left[leftIndices[x]]
            if (x < matches.size - 1) {
                val gapLeft = if (leftIndices[x + 1] > leftIndices[x] + 1) left.subList(leftIndices[x] + 1, leftIndices[x + 1]) else emptyList()
                val gapRight = if (rightIndices[x + 1] > rightIndices[x] + 1) right.subList(rightIndices[x] + 1, rightIndices[x + 1]) else emptyList()
                result += if (gapRight.size > gapLeft.size) gapRight else gapLeft
            }
        }
        val lastRight = rightIndices.last()
        if (lastRight + 1 < right.size) {
            val tail = right.subList(lastRight + 1, right.size)
            if (tail.first().id !in spliceSafe) {
                val wordStart = (lastRight downTo 0).firstOrNull { right[it].id in spliceSafe }
                val pop = (result.size - 1 downTo 0).firstOrNull { result[it].id in spliceSafe }
                if (wordStart != null && pop != null) {
                    while (result.size > pop) result.removeAt(result.size - 1)
                    result += right.subList(wordStart, right.size)
                } else {
                    var cursor = leftIndices.last() + 1
                    while (cursor < left.size && left[cursor].id !in spliceSafe) result += left[cursor++]
                    val resume = tail.indexOfFirst { it.id in spliceSafe }
                    result += if (resume >= 0) tail.subList(resume, tail.size) else tail
                }
            } else {
                result += tail
            }
        }
        return result
    }

    private fun midpoint(left: List<Token>, right: List<Token>, leftEnd: Double, rightStart: Double): List<Token> {
        val cutoff = (leftEnd + rightStart) / 2
        var leftStop = left.indexOfFirst { it.frame * SECONDS_PER_FRAME >= cutoff }.let { if (it < 0) left.size else it }
        var rightFrom = right.indexOfFirst { it.frame * SECONDS_PER_FRAME >= cutoff }.let { if (it < 0) right.size else it }
        if (leftStop > 0) while (leftStop < left.size && left[leftStop].id !in spliceSafe) leftStop++
        var scan = rightFrom
        while (scan < right.size && right[scan].id !in spliceSafe) scan++
        if (scan < right.size) rightFrom = scan
        return left.subList(0, leftStop) + right.subList(rightFrom, right.size)
    }

    private fun idsMatch(a: Int, b: Int): Boolean = a == b || (caseVariant[a] != null && caseVariant[a] == caseVariant[b])

    internal fun monotonic(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val out = ArrayList<Token>(tokens.size)
        var last = tokens[0].frame
        out += tokens[0]
        for (i in 1 until tokens.size) {
            val t = tokens[i]
            if (t.frame < last) out += t.copy(frame = last) else { last = t.frame; out += t }
        }
        return out
    }

    /** Adjacent case-only duplicate words within the overlap (ChunkProcessor :857-938). */
    internal fun collapseSeamDuplicates(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val overlapFrames = (OVERLAP_SECONDS / SECONDS_PER_FRAME).roundToInt()
        val words = ArrayList<MutableList<Token>>()
        for (t in tokens) if (words.isEmpty() || isBoundary(vocab[t.id])) words += mutableListOf(t) else words.last() += t
        val cores = words.map { w -> w.joinToString("") { vocab[it.id].removePrefix(BOUNDARY) }.trim { it.isWhitespace() || isPunctuation(it) } }
        val endsSentence = words.map { w -> w.joinToString("") { vocab[it.id].removePrefix(BOUNDARY) }.lastOrNull()?.let { it in ".?!:" } ?: false }
        val keep = BooleanArray(words.size) { true }
        var lastKept = -1
        for (i in words.indices) {
            if (lastKept < 0) { lastKept = i; continue }
            val previous = cores[lastKept]
            val current = cores[i]
            val duplicate = previous.isNotEmpty() && current.isNotEmpty() && previous != current &&
                previous.lowercase() == current.lowercase() && current.first().isLetter() && !endsSentence[lastKept] &&
                words[i].first().frame - words[lastKept].first().frame <= overlapFrames
            if (!duplicate) { lastKept = i; continue }
            if (current == current.lowercase() && previous != previous.lowercase()) { keep[lastKept] = false; lastKept = i } else keep[i] = false
        }
        return words.indices.filter { keep[it] }.flatMap { words[it] }
    }

    // ---- seam-gap repair (ChunkProcessor.repairSeamGaps :1394-1514)

    internal fun repairSeamGaps(samples: FloatArray, tokens: List<Token>): List<Token> {
        val n = samples.size
        val frameCount = n / FRAME
        val rms = FloatArray(frameCount) { f -> frameRms(samples, f * FRAME, FRAME) }
        val threshold = rms.filter { it > 0f }.sorted().let { sorted ->
            if (sorted.isEmpty()) SPEECH_RMS_CEILING else (sorted[min(sorted.size - 1, (sorted.size * 0.75).toInt())] * 0.3f).coerceIn(SPEECH_RMS_FLOOR, SPEECH_RMS_CEILING)
        }
        fun speechSeconds(from: Int, to: Int): Double {
            var frames = 0
            var f = from / FRAME
            val last = ceilDiv(to, FRAME)
            while (f < min(last, frameCount)) { if (rms[f] >= threshold) frames++; f++ }
            return frames * SECONDS_PER_FRAME
        }
        fun punctuationOnly(id: Int) = isPunctuationOrSymbol(vocab[id])
        fun neighbour(stream: List<Token>, from: Int, step: Int): Token {
            var i = from
            while (i + step in stream.indices && punctuationOnly(stream[i].id)) i += step
            return stream[i]
        }
        fun samePiece(a: Int, b: Int) = a == b || vocab[a].lowercase() == vocab[b].lowercase()
        val minGapFrames = max(2, (MIN_GAP_SECONDS / SECONDS_PER_FRAME).toInt())
        var working = tokens
        var probes = 0
        val probed = HashSet<Int>()
        repeat(3) {
            val inserts = ArrayList<Token>()
            for (i in 0 until working.size - 1) {
                if (probes >= MAX_REPAIRS) break
                val current = working[i]
                val next = working[i + 1]
                val gapStart = current.frame + max(1, current.duration)
                val gapEnd = next.frame
                if (gapEnd - gapStart < minGapFrames || gapStart in probed) continue
                val startSample = gapStart * FRAME
                val endSample = min(gapEnd * FRAME, n)
                if (endSample <= startSample || speechSeconds(startSample, endSample) < MIN_GAP_SPEECH_SECONDS) continue
                probed += gapStart
                probes++
                val centre = (startSample + endSample) / 2
                for (placement in intArrayOf(startSample, centre - REPAIR_WINDOW / 2)) {
                    val windowStart = max(0, min(placement, n - REPAIR_WINDOW)) / FRAME * FRAME
                    val windowEnd = min(windowStart + REPAIR_WINDOW, n)
                    if (windowEnd <= windowStart) continue
                    val window = decodeWindow(samples.copyOfRange(windowStart, windowEnd), 0, windowStart, windowEnd >= n)
                    val candidate = window.filter { it.frame in (gapStart + 1) until (gapEnd - 1) }.toMutableList()
                    while (candidate.isNotEmpty() && candidate.first().id !in spliceSafe) candidate.removeAt(0)
                    val lead = neighbour(working, i, -1)
                    val trail = neighbour(working, i + 1, 1)
                    while (candidate.isNotEmpty() && samePiece(candidate.first().id, lead.id) && abs(candidate.first().frame - lead.frame) <= EDGE_FRAMES) candidate.removeAt(0)
                    while (candidate.isNotEmpty() && samePiece(candidate.last().id, trail.id) && abs(trail.frame - candidate.last().frame) <= EDGE_FRAMES) candidate.removeAt(candidate.size - 1)
                    while (candidate.isNotEmpty() && (candidate.first().id !in spliceSafe || punctuationOnly(candidate.first().id))) candidate.removeAt(0)
                    if (candidate.isNotEmpty()) { inserts += candidate; break }
                }
            }
            if (inserts.isEmpty()) return working
            working = (working + inserts).sortedBy { it.frame }
        }
        return working
    }

    internal fun text(tokens: List<Token>): String = tokens.joinToString("") { vocab[it.id] }.replace(BOUNDARY, " ").trim()

    internal companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 1_280
        const val SECONDS_PER_FRAME = 0.08
        const val WINDOW_SAMPLES = 240_000
        const val CHUNK = 238_080
        const val OVERLAP = 32_000
        const val STRIDE = CHUNK - OVERLAP
        const val OVERLAP_SECONDS = 2.0
        const val MIN_SAMPLES = 4_800
        const val MAX_TOKENS_PER_WINDOW = 150
        const val MAX_SYMBOLS_PER_STEP = 10
        const val CONSECUTIVE_BLANK_LIMIT = 5
        val DURATION_BINS = intArrayOf(0, 1, 2, 3, 4)
        const val MIN_GAP_SECONDS = 1.5
        const val MIN_GAP_SPEECH_SECONDS = 0.5
        const val MAX_REPAIRS = 32
        const val EDGE_FRAMES = 6
        const val REPAIR_WINDOW = (WINDOW_SAMPLES - 160) / FRAME * FRAME
        const val SPEECH_RMS_FLOOR = 0.0005f
        const val SPEECH_RMS_CEILING = 0.008f
        const val BOUNDARY = "▁"

        fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 0 else (a + b - 1) / b

        fun frameRms(samples: FloatArray, from: Int, count: Int): Float {
            var sum = 0.0
            val end = min(samples.size, from + count)
            for (i in from until end) sum += samples[i] * samples[i]
            return if (end > from) sqrt(sum / (end - from)).toFloat() else 0f
        }

        /** Last speech-bearing sample: trailing 80 ms frames under the floor are trimmed (ChunkProcessor :112-128). */
        fun speechEnd(samples: FloatArray): Int {
            var end = samples.size
            while (end > 0) {
                val from = max(0, end - FRAME)
                if (frameRms(samples, from, end - from) >= SPEECH_RMS_FLOOR) return end
                end = from
            }
            return samples.size
        }

        /** A short final chunk fills its window backwards with real audio (ChunkProcessor :85-102). */
        fun lastChunkWarmup(start: Int, total: Int, speechEnd: Int): Int {
            val isLast = start + CHUNK >= total
            val remaining = min(speechEnd, total) - start
            if (!isLast || remaining <= 0 || start <= 0) return 0
            val fill = (CHUNK - remaining) / FRAME * FRAME
            if (fill <= 0) return 0
            return max(0, min(start / FRAME * FRAME, fill))
        }

        fun isBoundary(piece: String) = piece.startsWith(BOUNDARY) || piece.startsWith(" ")

        fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }

        private fun isSymbol(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        }

        /** A piece that is only punctuation once its word boundary is stripped. */
        fun isPunctuationPiece(piece: String): Boolean {
            val core = piece.removePrefix(BOUNDARY).trim()
            return core.isNotEmpty() && core.all(::isPunctuation)
        }

        fun isPunctuationOrSymbol(piece: String): Boolean = piece.isNotEmpty() && piece.all { isPunctuation(it) || isSymbol(it) }
    }
}
