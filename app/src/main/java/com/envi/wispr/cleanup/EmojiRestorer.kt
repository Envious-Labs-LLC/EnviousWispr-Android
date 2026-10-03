package com.envi.wispr.cleanup

/** Deletion-only grapheme restoration. The allocation is bounded by the alignment tokenizer. */
internal object EmojiRestorer {
    internal const val MAX_ALIGNMENT_TOKENS = 200
    internal const val MAX_INPUT_UTF16 = 4_096
    internal const val MAX_GRAPHEMES = 2_048
    internal const val MAX_EMOJI_CLUSTERS = 64
    private val grapheme = Regex("\\X")
    private val bound = setOf("%", "°", "+", "#", "*", "‰")
    private val noSpaceBefore = setOf(".", ",", "!", "?", ";", ":", ")", "]", "}", "%", "°", "'", "’", "…")
    private data class Word(val key: String, val start: Int, val end: Int)
    private data class Glyph(val index: Int, val text: String)
    private data class Run(val text: String, val start: Int, val end: Int, val firstGlyph: Int, val afterGlyph: Int)
    private fun emoji(value: String): Boolean {
        val cp = value.codePointAt(0)
        return cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF
    }
    private fun word(value: String) = Character.isLetterOrDigit(value.codePointAt(0)) && !emoji(value)
    private fun white(value: String) = value.all(Char::isWhitespace)
    private fun key(value: String): String = buildString {
        value.codePoints().forEach { cp -> if (cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF) appendCodePoint(cp) }
    }
    private data class Prepared(val chars: List<String>, val words: List<Word>, val glyphs: List<Glyph>)
    private data class Admission(val prepared: Prepared?, val consumed: Int)

    // One scanner owns token semantics and admission. Whole-input refusal never slices a cluster.
    private fun scan(text: String): Admission {
        if (text.length > MAX_INPUT_UTF16) return Admission(null, 0)
        val matches = grapheme.toPattern().matcher(text)
        val pending = java.util.ArrayDeque<String>()
        val chars = mutableListOf<String>()
        val words = mutableListOf<Word>()
        val glyphs = mutableListOf<Glyph>()
        var consumed = 0
        fun peek(offset: Int = 0): String? {
            while (pending.size <= offset) {
                if (!matches.find()) return null
                consumed++
                pending.addLast(matches.group())
            }
            return pending.elementAt(offset)
        }
        fun take(): Boolean {
            val value = pending.removeFirst()
            if (chars.size >= MAX_GRAPHEMES) return false
            if (emoji(value)) {
                if (glyphs.size >= MAX_EMOJI_CLUSTERS) return false
                glyphs += Glyph(chars.size, value)
            }
            chars += value
            return true
        }
        while (true) {
            val next = peek() ?: break
            if (!word(next)) {
                if (!take()) return Admission(null, consumed)
                continue
            }
            if (words.size >= MAX_ALIGNMENT_TOKENS) return Admission(null, consumed)
            val start = chars.size
            while (peek()?.let(::word) == true) if (!take()) return Admission(null, consumed)
            if (peek() in setOf("'", "’") && peek(1)?.let(::word) == true) {
                if (!take()) return Admission(null, consumed)
                while (peek()?.let(::word) == true) if (!take()) return Admission(null, consumed)
            }
            words += Word(chars.subList(start, chars.size).joinToString("").lowercase(java.util.Locale.ROOT).replace('’', '\''), start, chars.size)
        }
        return Admission(Prepared(chars, words, glyphs), consumed)
    }
    internal fun scannedGraphemeCount(text: String): Int = scan(text).consumed
    fun restore(prePolish: String, polished: String): String {
        if (polished.isBlank()) return polished
        val before = scan(prePolish).prepared ?: return polished
        if (before.glyphs.isEmpty()) return polished
        val after = scan(polished).prepared ?: return polished
        val pre = before.chars
        val post = after.chars
        val glyphs = before.glyphs
        val a = before.words; val b = after.words
        fun leftKey(words: List<Word>, i: Int) = words.lastOrNull { it.end <= i }?.key.orEmpty()
        val kept = post.mapIndexedNotNull { i, value -> if (emoji(value)) Glyph(i, value) else null }
        val used = BooleanArray(kept.size); val keptIndex = IntArray(glyphs.size) { -1 }
        for (anchored in listOf(true, false)) {
            glyphs.forEachIndexed { index, glyph ->
                if (keptIndex[index] >= 0) return@forEachIndexed
                val found = kept.indices.firstOrNull { !used[it] && key(kept[it].text) == key(glyph.text) && (!anchored || leftKey(a, glyph.index) == leftKey(b, kept[it].index)) }
                if (found != null) { used[found] = true; keptIndex[index] = kept[found].index }
            }
        }
        if (keptIndex.all { it >= 0 }) return polished
        val runs = mutableListOf<Run>(); var k = 0
        while (k < glyphs.size) {
            if (keptIndex[k] >= 0) { k++; continue }
            val first = k; val start = glyphs[k].index; var end = start + 1
            k++
            while (k < glyphs.size && keptIndex[k] < 0 && pre.subList(end, glyphs[k].index).all(::white)) { end = glyphs[k].index + 1; k++ }
            runs += Run(pre.subList(start, end).joinToString(""), start, end, first, k)
        }
        val width = b.size + 1
        val dp = IntArray((a.size + 1) * width)
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i * width + j] = if (a[i].key == b[j].key) dp[(i + 1) * width + j + 1] + 1 else maxOf(dp[(i + 1) * width + j], dp[i * width + j + 1])
        val image = IntArray(a.size) { -1 }; var ai = 0; var bi = 0
        while (ai < a.size && bi < b.size) {
            if (a[ai].key == b[bi].key) { image[ai] = bi; ai++; bi++ }
            else if (dp[(ai + 1) * width + bi] >= dp[ai * width + bi + 1]) ai++ else bi++
        }
        val sentences = mutableListOf<IntRange>(); var sentenceStart = 0; var pos = 0
        while (pos < pre.size) {
            if (pre[pos] in setOf(".", "!", "?") && !(pre[pos] == "." && pos > 0 && pos + 1 < pre.size && word(pre[pos - 1]) && word(pre[pos + 1]))) {
                var end = pos + 1; while (end < pre.size && pre[end] in setOf(".", "!", "?")) end++
                sentences += sentenceStart until end; sentenceStart = end; pos = end
            } else pos++
        }
        if (sentenceStart < pre.size) sentences += sentenceStart until pre.size
        fun leftSeparator(start: Int): Boolean {
            var p = start - 1; while (p >= 0 && white(pre[p])) p--
            return p >= 0 && !word(pre[p]) && !emoji(pre[p]) && pre[p] !in bound
        }
        fun place(run: Run): Int {
            val sentence = sentences.firstOrNull { run.start in it } ?: pre.indices
            val l = a.indices.lastOrNull { a[it].start >= sentence.first && a[it].end <= run.start }
            val r = a.indices.firstOrNull { a[it].start >= run.end && a[it].end - 1 <= sentence.last }
            val hugLeft = !leftSeparator(run.start)
            var afterRun = run.end; while (afterRun < pre.size && white(pre[afterRun])) afterRun++
            val realEnder = afterRun < pre.size && pre[afterRun] in setOf(".", "!", "?")
            val rightSurvives = r != null && image[r] >= 0
            fun afterLeft(index: Int): Int {
                var p = b[image[index]].end
                while (p < post.size) {
                    if (post[p] in bound) { p++; continue }
                    if (post[p] in setOf("-", "‑") && p + 1 < post.size && word(post[p + 1])) { p++; while (p < post.size && word(post[p])) p++; continue }
                    break
                }
                if (rightSurvives && !realEnder || leftSeparator(run.start) && !rightSurvives) while (p < post.size && post[p] in setOf(".", "!", "?")) p++
                return p
            }
            var p = when {
                hugLeft && l != null && image[l] >= 0 -> afterLeft(l)
                !hugLeft && r != null && image[r] >= 0 -> b[image[r]].start
                !hugLeft && r == null && l != null && image[l] >= 0 -> afterLeft(l)
                else -> {
                    fun scan(range: IntRange): Int? = a.indices.filter { image[it] >= 0 && a[it].start >= range.first && a[it].end - 1 <= range.last && (a[it].end <= run.start || a[it].start >= run.end) }
                        .sortedWith(compareBy<Int> { if (a[it].end <= run.start) run.start - a[it].end else a[it].start - run.end }.thenBy { (a[it].end <= run.start) != hugLeft })
                        .firstOrNull()?.let { if (a[it].end <= run.start) afterLeft(it) else b[image[it]].start }
                    scan(sentence) ?: scan(pre.indices) ?: post.size
                }
            }
            var lo = 0; var hi = post.size
            if (run.firstGlyph > 0 && keptIndex[run.firstGlyph - 1] >= 0 && pre.subList(glyphs[run.firstGlyph - 1].index + 1, run.start).all(::white)) lo = keptIndex[run.firstGlyph - 1] + 1
            if (run.afterGlyph < glyphs.size && keptIndex[run.afterGlyph] >= 0 && pre.subList(run.end, glyphs[run.afterGlyph].index).all(::white)) hi = keptIndex[run.afterGlyph]
            if (lo <= hi) p = p.coerceIn(lo, hi)
            return p
        }
        val insertions = runs.map { place(it) to it.text }.sortedBy { it.first }
        val out = StringBuilder(); var cursor = 0
        for ((target, glyph) in insertions) {
            val p = target.coerceIn(cursor, post.size)
            out.append(post.subList(cursor, p).joinToString(""))
            if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            out.append(glyph)
            if (p < post.size && !white(post[p]) && post[p] !in noSpaceBefore) out.append(' ')
            cursor = p
        }
        return out.append(post.subList(cursor, post.size).joinToString("")).toString()
    }
}
