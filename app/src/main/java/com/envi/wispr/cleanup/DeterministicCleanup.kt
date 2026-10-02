package com.envi.wispr.cleanup

import java.text.NumberFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal data class CleanupOptions(
    val removeFillers: Boolean = true,
    val spokenEmoji: Boolean = true,
    val spokenPunctuation: Boolean = false,
    val englishSpelling: EnglishSpelling = EnglishSpelling.AMERICAN,
    val spellingProtectedWords: Set<String> = emptySet(),
)

internal data class CleanupResult(val text: String, val changed: Boolean, val recovered: Boolean)

/** Deterministic English cleanup plus the language-neutral identifier/address subset. No network. */
internal object DeterministicCleanup {
    // `um` and `err` were removed 2026-09-02 (#36, #107). Both are ordinary WORDS, so stripping them
    // deletes something the speaker authored, and RULE: matcher-set-adversarial-tests says that direction
    // fails worse than leaving a filler in. `err` is an English verb: "To err is human" became "To is
    // human". `um` is a German preposition, a Portuguese article, and a Croatian and Slovenian noun, all
    // inside the 25 languages Parakeet v3 decodes. The remaining six are not words in those languages.
    private val baseFillers = listOf("uh", "erm", "ah", "hmm", "hm", "mhm")

    /**
     * One compiled matcher per member of [CleanupLanguagePolicy.allExtraFillerSets]. The population of
     * extra-token sets is CLOSED and comes from the policy, so every state a dictation can be in is
     * compiled once here rather than per take. `CleanupLanguagePolicy.allExtraFillerSets` is DERIVED
     * from the same map the policy reads, so a language cannot be added without adding its set here, which
     * is what makes `getValue` safe. The sampled test in `CleanupLanguagePolicyTest` is only a
     * lookup-safety smoke test and would stay green against a hand-written list of the same members;
     * the derivation, not the test, is what closes the window.
     */
    private val fillerByExtras: Map<Set<String>, Regex> =
        CleanupLanguagePolicy.allExtraFillerSets.associateWith { extras ->
            val unitTokens = listOf("ah") + if ("mm" in extras) listOf("mm") else emptyList()
            val ordinaryTokens = (baseFillers + extras.sorted()).filterNot { it in unitTokens }
            cleaningRegex("(?<!\\p{Nd})(?<!\\p{Nd} )(?<!\\p{Nd}-)\\b(?!(?-i:[A-Z]{2,})\\b)(${unitTokens.joinToString("|")})\\b[,.!?;:]*\\s*|\\b(?!(?-i:[A-Z]{2,})\\b)(${ordinaryTokens.joinToString("|") { Regex.escape(it) }})\\b[,.!?;:]*\\s*", RegexOption.IGNORE_CASE)
        }

    internal fun fillerMatcher(language: CleanupLanguage): Regex =
        fillerByExtras.getValue(CleanupLanguagePolicy.extraFillers(language))
    private val punctuation = linkedMapOf(
        "new paragraph" to "\n\n", "new line" to "\n", "question mark" to "?",
        "exclamation mark" to "!", "exclamation point" to "!", "full stop" to ".",
        "semicolon" to ";", "period" to ".", "comma" to ",", "colon" to ":",
    )
    private val protectedPhrases = listOf(
        cleaningRegex("\\beleventh hour\\b", RegexOption.IGNORE_CASE),
        cleaningRegex("\\bthe whole nine yards\\b", RegexOption.IGNORE_CASE),
        cleaningRegex("\\b(?:a |an )?(?:quarter|half)\\s+(?:past|to)\\s+\\w+", RegexOption.IGNORE_CASE),
        cleaningRegex("\\b(?:a |an )?(?:couple|few|several|many)\\s+hundred\\b", RegexOption.IGNORE_CASE),
    )
    internal val units = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "one" to 1, "two" to 2, "three" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    internal val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val scales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val numberWords = units.keys + tens.keys + scales.keys + "and"
    private val numberAlt = numberWords.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    internal val numberNoAndAlt = numberWords.filterNot { it == "and" }.sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) }
    private val numberRun = "(?:$numberAlt|\\d[\\d,]*)(?:[ -]+(?:$numberAlt|\\d[\\d,]*))*"
    private val numberRunNoLeadingAnd =
        "(?:$numberNoAndAlt|\\d[\\d,]*)(?:[ -]+(?:$numberAlt|\\d[\\d,]*))*"
    private val digitAlt = units.filterValues { it < 10 }.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val months = linkedMapOf(
        "january" to "January", "february" to "February", "march" to "March", "april" to "April",
        "may" to "May", "june" to "June", "july" to "July", "august" to "August",
        "september" to "September", "october" to "October", "november" to "November", "december" to "December",
    )
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15,
        "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
        "twentieth" to 20, "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50,
        "sixtieth" to 60, "seventieth" to 70, "eightieth" to 80, "ninetieth" to 90,
    )
    private val ordinalAlt = ordinals.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val dateOrdinals = buildMap {
        putAll(ordinals)
        tens.forEach { (word, value) ->
            ordinals.filterValues { it in 1..9 }.forEach { (tail, tailValue) ->
                put("$word $tail", value + tailValue)
            }
        }
    }
    private val dateOrdinalAlt = dateOrdinals.keys.sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) }
    private val unitNouns = setOf(
        "mile", "miles", "foot", "feet", "inch", "inches", "yard", "yards", "pound", "pounds",
        "ounce", "ounces", "kg", "kilogram", "kilograms", "gram", "grams", "km", "kilometer",
        "kilometers", "meter", "meters", "cm", "centimeter", "centimeters", "liter", "liters",
        "gallon", "gallons", "cup", "cups", "tablespoon", "tablespoons", "teaspoon", "teaspoons",
        "degree", "degrees", "mph", "percent", "milligram", "milligrams", "mg", "milliliter",
        "milliliters", "ml", "millimeter", "millimeters", "mm", "lb", "lbs", "oz", "metre",
        "metres", "litre", "litres", "tbsp", "tsp",
    )
    private val agePeriods = setOf("year", "years", "month", "months", "week", "weeks", "day", "days")

    /**
     * [trace] receives the text after each enabled family (#378: the local log's words per cleanup step). It is
     * a diagnostic observer only: it never changes the result, and a throw from it is the caller's problem,
     * caught below like any other family failure.
     */
    fun apply(
        raw: String,
        options: CleanupOptions = CleanupOptions(),
        language: CleanupLanguage = CleanupLanguage.Unknown,
        trace: (family: String, text: String) -> Unit = NO_TRACE,
    ): CleanupResult {
        if (raw.isBlank()) return CleanupResult("", raw.isNotEmpty(), false)
        val original = raw.trim()
        // Every English-shaped rewriting family below is gated on this ONE answer, so a language the app
        // could not establish takes exactly the path it took before #107. Filler removal is deliberately
        // NOT gated on it: the shared six are safe in all 25 languages and the English extras are added
        // by the same policy, which is macOS `FillerRemovalStep` read from the English side.
        val skipEnglishRewrites = CleanupLanguagePolicy.skipsEnglishRewrites(language)
        var lastGood = original
        return try {
            var value = original
            if (options.removeFillers) {
                value = fillerMatcher(language).replace(value) { match ->
                    val token = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
                    if (token.lowercase(Locale.ROOT) in options.spellingProtectedWords) match.value else ""
                }
                if (!TextSafety.isDeterministicSafe(lastGood, value, false)) return CleanupResult(lastGood, lastGood != original, true)
                lastGood = value
                trace("fillers", value)
            }
            val beforeEmoji = value
            if (options.spokenEmoji && !skipEnglishRewrites) value = SpokenEmojiFormatter.format(value)
            val emojiChanged = value != beforeEmoji
            if (!TextSafety.isDeterministicSafe(lastGood, value, emojiChanged)) return CleanupResult(lastGood, lastGood != original, true)
            lastGood = value
            if (options.spokenEmoji && !skipEnglishRewrites) trace("emoji", value)
            // The placeholder insert and its restore are one unit and are skipped together; leaving the
            // insert reachable without the restore would ship private-use characters into the editor.
            var structuredChanged = false
            if (!skipEnglishRewrites) {
                val protected = ProtectedText(value)
                protectedPhrases.forEach { phrase ->
                    value = phrase.replace(value) { match ->
                        protected.protect(match.value)
                    }
                }
                value = cleaningRegex("\\b(?:a|an)\\s+(hundred\\b)(?!-)").replace(value, "$1")
                value = cleaningRegex(
                    "(?i)\\b(a|an|the|this|that|another)\\s+catch[\\s-]+(?:twenty[\\s-]+two|22)\\b",
                ).replace(value) { "${it.groupValues[1]} Catch-22" }
                val beforeStructured = value
                value = normalizeStructured(value)
                structuredChanged = value != beforeStructured
                value = protected.restore(value)
                if (!TextSafety.isDeterministicSafe(lastGood, value, structuredChanged)) return CleanupResult(lastGood, lastGood != original, true)
                lastGood = value
                trace("structured", value)
            }
            if (options.spokenPunctuation && !skipEnglishRewrites) punctuation.forEach { (phrase, mark) ->
                val command = if ('\n' in mark) {
                    cleaningRegex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
                } else {
                    cleaningRegex("\\s*\\b${Regex.escape(phrase)}\\b\\s*", RegexOption.IGNORE_CASE)
                }
                val replacement = if ('\n' in mark) mark else "$mark "
                value = value.replace(command, replacement)
            }
            if (!skipEnglishRewrites) value = SpokenSlash.apply(value, options.spokenPunctuation)
            else value = SpokenIdentifiers.normalize(NeutralAddresses.normalize(value, neutral = true), english = false)
            if (!TextSafety.isDeterministicSafe(lastGood, value, true)) return CleanupResult(lastGood, lastGood != original, true)
            lastGood = value
            if (options.spokenPunctuation && !skipEnglishRewrites) trace("punctuation", value)
            value = formatText(value)
            value = BritishSpelling.convert(value, options.englishSpelling, language, options.spellingProtectedWords)
            if (!TextSafety.isDeterministicSafe(original, value, structuredChanged || emojiChanged)) return CleanupResult(lastGood, lastGood != original, true)
            lastGood = value
            trace("format", value)
            CleanupResult(value, value != original, false)
        } catch (_: RuntimeException) {
            CleanupResult(lastGood, lastGood != original, true)
        } catch (_: StackOverflowError) {
            // Catastrophic backtracking in one of the regex families unwinds the stack rather than
            // corrupting it, so it is recoverable, and a limb must never throw into the session path
            // (`kotlin-patterns.md` RULE: fail-open-to-the-last-good-text).
            //
            // `Throwable` is deliberately NOT caught. An OutOfMemoryError is not recoverable, and
            // swallowing it here would hide a dying process behind a transcript that merely looks
            // uncleaned.
            //
            // Only completed plain-text stages are eligible here. Sentinels are restored and validated
            // inside the structured stage before it can become lastGood.
            CleanupResult(lastGood, lastGood != original, true)
        }
    }

    /** The default [apply] observer: nothing. */
    val NO_TRACE: (String, String) -> Unit = { _, _ -> }

    private fun normalizeStructured(input: String): String {
        var hyphenated = input
        val hyphenJoin = cleaningRegex("\\b((?i:$numberNoAndAlt))-(?=(?:$numberNoAndAlt|$ordinalAlt)\\b)")
        do {
            val previous = hyphenated
            hyphenated = hyphenJoin.replace(hyphenated, "$1 ")
        } while (hyphenated != previous)
        val protected = ProtectedText(input)
        fun protect(value: String) = protected.protect(value)
        var text = " $hyphenated "
        text = cleaningRegex("(?i)\\bat\\s+one\\s+point\\b(?=\\s+(?:\\d+|(?:$numberNoAndAlt)(?:\\s+(?:$numberNoAndAlt))*)\\s+point\\b)").replace(text) { protect(it.value) }
        text = NeutralAddresses.normalize(text, neutral = false)
        text = urls(text)
        text = SpokenIdentifiers.normalize(text, english = true)
        val part = "(?:\\d+|(?:$numberNoAndAlt)(?:\\s+(?:$numberNoAndAlt))*)"
        text = cleaningRegex("(?i)\\b(?:$part(?:\\s+(?:dot|point|punkt|punto|ponto)\\s+$part){2,}|\\d+(?:\\.\\d+)+(?:\\s+(?:dot|point|punkt|punto|ponto)\\s+$part)+)\\b").replace(text) { protect(it.value) }
        text = decimals(text)
        text = moneyPercent(text)
        text = times(text)
        text = dates(text)
        text = ListMarkers.apply(text)
        text = ordinalNumbers(text)
        text = StreetAddresses.apply(text, ::protect)
        text = years(text)
        text = moneyPercent(text)
        text = digitRuns(text)
        text = digitScales(text)
        text = rangesAndDimensions(text)
        text = dosageRuns(text)
        text = cardinals(text)
        text = keepMagnitude(text)
        text = protected.restore(text)
        return text.trim()
    }

    private fun urls(input: String): String {
        val tlds = "com|org|io|co|dev|me|net"
        var text = input.replace(cleaningRegex("(?i)\\b(?:h|aitch)\\s+slash\\s+slash(?=\\s+[a-z0-9])"), "https slash slash")
        text = cleaningRegex(
            "(?<![@\\w.-])([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)\\s+dot\\s+($tlds)((?:\\s+slash\\s+[a-z0-9-]+)*)",
            RegexOption.IGNORE_CASE,
        ).replace(text) {
            if (hasUnresolvedUrlContext(text, it) || it.groupValues[1].lowercase() in CleanupReferenceTables.englishProseDomainWords) it.value
            else it.groupValues[1] + "." + it.groupValues[2] + cleaningRegex("(?i)\\s+slash\\s+").replace(it.groupValues[3], "/")
        }
        text = cleaningRegex(
            "(?<![@\\w.-])([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.(?:$tlds|ai|app|xyz))((?:\\s+slash\\s+[a-z0-9-]+)+)",
            RegexOption.IGNORE_CASE,
        ).replace(text) {
            if (hasUnresolvedUrlContext(text, it) || it.groupValues[1].lowercase() in CleanupReferenceTables.englishProseDomainWords) it.value
            else it.groupValues[1] + cleaningRegex("(?i)\\s+slash\\s+").replace(it.groupValues[2], "/")
        }
        text = text.replace(cleaningRegex("(?i)(\\b[a-z0-9][a-z0-9.-]*\\.(?:$tlds|ai|app|xyz))\\s+dot\\.?\\s*$"), "$1")
        return if (cleaningRegex("(?i)^\\s*(?:https?://)?[a-z0-9.-]+\\.(?:$tlds|ai|app|xyz)(?:/[a-z0-9-]+)*\\.\\s*$").matches(text)) text.trim().removeSuffix(".") else text
    }

    private fun hasUnresolvedUrlContext(text: String, match: MatchResult): Boolean {
        val before = text.substring(0, match.range.first).trimEnd().lowercase()
        val after = text.substring(match.range.last + 1).trimStart().lowercase()
        val beforeWords = before.split(cleaningRegex("\\s+")).filter(String::isNotEmpty)
        val emailLikeAt = beforeWords.size <= 2 && beforeWords.lastOrNull() == "at"
        val blockedBefore = emailLikeAt || listOf("@", "slash slash", " dot", " dash").any(before::endsWith)
        val blockedAfter = cleaningRegex("^(?:slash|dot|underscore|dash|question mark|equals?|colon|at)\\b")
            .containsMatchIn(after)
        val letterSpelledHost = beforeWords.takeLast(3).size == 3 &&
            beforeWords.takeLast(3).all { word -> word.length == 1 } &&
            cleaningRegex("^dot(?:\\s+[a-z]){2,}\\b", RegexOption.IGNORE_CASE).containsMatchIn(after)
        return blockedBefore || blockedAfter && !letterSpelledHost
    }

    private fun decimals(input: String): String {
        var text = cleaningRegex("(?i)\\b($numberRun)\\s+(?:point|dot)\\s+((?:$digitAlt)(?:\\s+(?:$digitAlt))*)(?:\\s+(thousand|million|billion))?\\b").replace(input) { match ->
            val rawWhole = match.groupValues[1]
            val digitRead = if (rawWhole.trim().split(cleaningRegex("\\s+")).size > 1) spokenDigits(rawWhole) else null
            val whole = digitRead ?: wordsToLong(rawWhole)?.toString() ?: return@replace match.value
            val digits = spokenDigits(match.groupValues[2]) ?: return@replace match.value
            val scale = scales[match.groupValues[3].lowercase()]
            if (scale == null) "$whole.$digits" else runCatching {
                BigDecimal("$whole.$digits").multiply(BigDecimal(scale))
                    .setScale(0, RoundingMode.HALF_EVEN).longValueExact().let(::comma)
            }.getOrElse { match.value }
        }
        return cleaningRegex("(?i)\\b(?:(negative|minus)\\s+)?point\\s+((?:$digitAlt)(?:\\s+(?:$digitAlt))*)\\b").replace(text) {
            val digits = spokenDigits(it.groupValues[2]) ?: return@replace it.value
            if (it.groupValues[1].isBlank() && digits.length < 3) it.value else "${if (it.groupValues[1].isBlank()) "" else "negative "}0.$digits"
        }
    }

    private fun moneyPercent(input: String): String {
        var text = cleaningRegex("(?i)(?<![\\d.])\\b($numberRun)\\s+dollars?\\s+($numberRunNoLeadingAnd)\\s+cents?\\b").replace(input) {
            val dollars = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val cents = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            "$${comma(dollars)} $${"%.2f".format(Locale.US, cents / 100.0)}"
        }
        text = cleaningRegex("(?i)(?<![\\d.])\\b($numberRun)\\s+dollars?(?:\\s+and\\s+($numberRun)\\s+cents?)?\\b").replace(text) {
            val dollars = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (it.groupValues[2].isBlank()) "$${comma(dollars)}" else {
                val cents = wordsToLong(it.groupValues[2]) ?: return@replace it.value
                "$${comma(dollars)}.${cents.toString().padStart(2, '0')}"
            }
        }
        text = cleaningRegex("(?i)(?<![\\d.])\\b($numberRun)\\s+cents?\\b").replace(text) {
            val cents = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            "$${"%.2f".format(Locale.US, cents / 100.0)}"
        }
        text = cleaningRegex("(?i)\\b($numberRun)\\s+(?:percent|per\\s+cent)\\b").replace(text) {
            val value = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            "$value%"
        }
        return cleaningRegex("(?i)\\b(\\d[\\d,]*\\.\\d+)\\s+(?:percent|per\\s+cent)\\b").replace(text, "$1%")
    }

    private fun times(input: String): String {
        val hourToken = "(?:$numberNoAndAlt|\\d{1,2})"
        var text = cleaningRegex("(?i)\\b(?:oh|o)\\s+($digitAlt)\\s+hundred\\b").replace(input) {
            val hour = units[it.groupValues[1].lowercase()] ?: return@replace it.value
            "${hour}00"
        }
        text = cleaningRegex("(?i)(?<!\\S)($hourToken)(?:\\s+($numberRun))?\\s+([ap])\\s*m\\b").replace(text) {
            val hour = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val minute = if (it.groupValues[2].isBlank()) 0 else wordsToLong(it.groupValues[2]) ?: spokenDigits(it.groupValues[2])?.toLongOrNull() ?: return@replace it.value
            if (hour !in 1..12 || minute !in 0..59) it.value else "$hour:${minute.toString().padStart(2, '0')} ${it.groupValues[3].uppercase()}M"
        }
        return cleaningRegex("(?i)\\b($numberRun)\\s+o'?clock\\b").replace(text) {
            val hour = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (hour in 1..12) "$hour:00" else it.value
        }
    }

    private fun dates(input: String): String {
        val monthAlt = months.keys.joinToString("|")
        return cleaningRegex("(?i)\\b($monthAlt)\\s+($dateOrdinalAlt|\\d{1,2}),?\\s+($numberRun)\\b").replace(input) {
            val day = dateOrdinals[it.groupValues[2].lowercase()] ?: it.groupValues[2].toIntOrNull() ?: return@replace it.value
            val year = parseYear(it.groupValues[3]) ?: return@replace it.value
            if (day !in 1..31 || year !in 1000..2999) it.value else "${months.getValue(it.groupValues[1].lowercase())} $day, $year"
        }
    }

    private fun years(input: String): String {
        val centuries = "fifteen|sixteen|seventeen|eighteen|nineteen|twenty"
        val lowTens = tens.keys.joinToString("|")
        val lowUnits = units.filterValues { it in 1..9 }.keys.joinToString("|")
        val lowTeens = units.filterValues { it in 10..19 }.keys.joinToString("|")
        val centuryLow = "(?:(?:$lowTens)(?:\\s+(?:$lowUnits))?|(?:$lowTeens)|(?:oh|o)\\s+(?:$lowUnits))"
        val twoThousandLow = "(?:(?:$lowTens)(?:\\s+(?:$lowUnits))?|(?:$lowTeens)|(?:$lowUnits)|(?:oh|o)\\s+(?:$lowUnits))"
        var text = cleaningRegex(
            "(?i)\\btwo thousand(?:\\s+and)?\\s+($twoThousandLow)\\b" +
                "(?!\\s+(?:hundred|thousand|million|billion))",
        ).replace(input) {
            val low = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (low in 1..99) "${2000 + low}" else it.value
        }
        text = cleaningRegex("(?i)\\b($centuries)\\s+($centuryLow)\\b").replace(text) {
            val century = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val lowRaw = it.groupValues[2]
            val low = if (cleaningRegex("(?i)^(oh|o)\\s+").containsMatchIn(lowRaw)) units[lowRaw.trim().split(cleaningRegex("\\s+")).last().lowercase()]?.toLong() else wordsToLong(lowRaw)
            if (low != null && low in 1..99) "${century * 100 + low}" else it.value
        }
        return text
    }

    private fun ordinalNumbers(input: String): String {
        val scaleOrdinals = mapOf(
            "hundredth" to "hundred",
            "thousandth" to "thousand",
            "millionth" to "million",
            "billionth" to "billion",
        )
        val scaleOrdinalAlt = scaleOrdinals.keys.joinToString("|")
        var text = cleaningRegex("(?i)\\b(?:($numberRun)\\s+)?($scaleOrdinalAlt)\\b").replace(input) {
            val scaleWord = scaleOrdinals.getValue(it.groupValues[2].lowercase())
            val cardinal = listOf(it.groupValues[1], scaleWord).filter(String::isNotBlank).joinToString(" ")
            val value = wordsToLong(cardinal) ?: return@replace it.value
            val after = input.substring(it.range.last + 1).trimStart()
            if (after.startsWith("of ", ignoreCase = true)) it.value else "${comma(value)}${ordinalSuffix(value.toInt())}"
        }
        text = cleaningRegex("(?i)\\b($numberRun)\\s+($ordinalAlt)\\b").replace(text) {
            val tailWord = it.groupValues[2].lowercase()
            val after = text.substring(it.range.last + 1).trimStart()
            if (tailWord == "second" && durationNoun(after)) return@replace it.value
            if (after.startsWith("of ", ignoreCase = true)) return@replace it.value
            val base = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val lastCardinal = it.groupValues[1].lowercase().split(cleaningRegex("\\s+"))
                .lastOrNull { word -> word != "and" }
            if (lastCardinal !in scales) return@replace it.value
            val value = base + ordinals.getValue(tailWord)
            "${comma(value)}${ordinalSuffix(value.toInt())}"
        }
        val unitOrdinal = ordinals.filterValues { it in 1..9 }.keys.joinToString("|")
        text = cleaningRegex("(?i)\\b(${tens.keys.joinToString("|")})\\s+($unitOrdinal)\\b").replace(text) {
            val tailWord = it.groupValues[2].lowercase()
            val after = text.substring(it.range.last + 1).trimStart()
            if (tailWord == "second" && durationNoun(after)) return@replace it.value
            val value = tens.getValue(it.groupValues[1].lowercase()) + ordinals.getValue(tailWord)
            "$value${ordinalSuffix(value)}"
        }
        return cleaningRegex("(?i)\\b($ordinalAlt)\\b").replace(text) {
            val value = ordinals.getValue(it.value.lowercase())
            if (value < 10) it.value else "$value${ordinalSuffix(value)}"
        }
    }

    private fun durationNoun(after: String): Boolean {
        val next = after.takeWhile { it.isLetter() }.lowercase()
        return next in setOf(
            "second", "seconds", "video", "clip", "ad", "ads", "advert", "advertisement",
            "commercial", "timer", "countdown", "break", "intro", "introduction", "delay", "pause", "window",
            "interval", "mark", "segment", "spot", "trailer", "teaser", "rule", "gap", "lead", "burst",
            "sprint", "rest", "head",
        )
    }

    private fun digitRuns(input: String): String = cleaningRegex("(?i)\\b(?:$digitAlt|\\d{1,4})(?:\\s+(?:$digitAlt|\\d{1,4})){1,}\\b").replace(input) {
        val tokens = it.value.split(cleaningRegex("\\s+"))
        val digits = tokens.joinToString("") { token -> units[token.lowercase()]?.takeIf { n -> n < 10 }?.toString() ?: token }
        when {
            digits.length == 10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            digits.length == 7 -> "${digits.take(3)}-${digits.takeLast(4)}"
            tokens.any { token -> token.lowercase() in setOf("zero", "oh", "o") } && digits.length <= 6 -> digits
            else -> it.value
        }
    }

    private fun rangesAndDimensions(input: String): String {
        var text = cleaningRegex("(?i)\\bbetween\\s+($numberRun)\\s+and\\s+($numberRun)\\b").replace(input) {
            val a = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val b = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            if (it.groupValues[1].contains(cleaningRegex("(?i)\\band\\b"))) {
                "between ${comma(a)} and ${comma(b)}"
            } else {
                "between ${comma(a)}-${comma(b)}"
            }
        }
        text = cleaningRegex("(?i)\\b($numberRun)\\s+(?:to|through)\\s+($numberRun)\\b").replace(text) {
            val a = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val b = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            "${comma(a)}-${comma(b)}"
        }
        text = cleaningRegex("(?i)(?<![\\d.])\\b($numberRun)(?:\\s+slash\\s+$numberRun)+\\b").replace(text) {
            val parts = it.value.split(cleaningRegex("(?i)\\s+slash\\s+"))
            val values = parts.map(::wordsToLong)
            if (values.any { value -> value == null }) it.value else values.joinToString("/") { value -> value.toString() }
        }
        text = cleaningRegex("(?i)(?<![\\d.])\\b($numberRun)(?:\\s+by\\s+$numberRun)+\\b").replace(text) {
            val parts = it.value.split(cleaningRegex("(?i)\\s+by\\s+"))
            val values = parts.map(::wordsToLong)
            val after = text.substring(it.range.last + 1).trimStart()
            val followedByUnit = after.takeWhile(Char::isLetter).lowercase() in unitNouns
            if (values.any { value -> value == null } || values.all { value -> value == 1L } && !followedByUnit) {
                it.value
            } else {
                values.joinToString(" by ") { value -> comma(value!!) }
            }
        }
        return cleaningRegex("(?<![-\\d.])\\b(\\d+)\\s+to\\s+(\\d+)\\b").replace(text, "$1-$2")
    }

    private fun digitScales(input: String): String = cleaningRegex(
        "(?i)\\b(\\d[\\d,]*\\s+(?:hundred|thousand)(?:\\s+(?:$numberAlt))*)\\b",
    ).replace(input) {
        wordsToLong(it.groupValues[1])?.let(::comma) ?: it.value
    }

    private fun dosageRuns(input: String): String {
        val oneToNine = units.filterValues { it in 1..9 }.keys.joinToString("|")
        val dosageUnits = "milligram|milligrams|mg|milliliter|milliliters|ml"
        return cleaningRegex("(?i)\\b($numberNoAndAlt)\\s+($oneToNine)(?=\\s+(?:$dosageUnits)\\b)").replace(input) {
            val digit = units[it.groupValues[2].lowercase()] ?: return@replace it.value
            "${it.groupValues[1]} $digit"
        }
    }

    private fun cardinals(input: String): String = cleaningRegex("(?i)\\b(?:$numberNoAndAlt)(?:\\s+(?:$numberAlt))*\\b").replace(input) {
        val words = it.value.trim().split(cleaningRegex("\\s+")).toMutableList()
        var trailingAnd = false
        if (words.lastOrNull()?.equals("and", ignoreCase = true) == true) {
            words.removeLast()
            trailingAnd = true
        }
        val cardinalText = words.joinToString(" ")
        val value = wordsToLong(cardinalText) ?: return@replace it.value
        val after = input.substring(it.range.last + 1).trimStart()
        val next = after.takeWhile(Char::isLetter).lowercase()
        val before = input.substring(0, it.range.first).trimEnd()
        val sentenceInitial = before.isBlank() || before.lastOrNull()?.let { it in ".!?\n\"'([" } == true
        val titleLike = words.drop(1).any { word -> word.firstOrNull()?.isUpperCase() == true }
        val firstCapital = words.firstOrNull()?.firstOrNull()?.isUpperCase() == true
        val allCaps = cardinalText.any(Char::isLetter) && cardinalText == cardinalText.uppercase()
        val inputLetters = input.filter(Char::isLetter)
        val shout = inputLetters.length > 1 && inputLetters == inputLetters.uppercase()
        val beforeWord = before.takeLastWhile(Char::isLetter)
        val afterWord = after.takeWhile(Char::isLetter)
        val adjacentAllCaps = listOf(beforeWord, afterWord).any { word ->
            word.length > 1 && word == word.uppercase()
        }
        val capitalizedSingleTitle = firstCapital && words.size == 1 && afterWord.firstOrNull()?.isUpperCase() == true
        val hyphenWord = after.removePrefix("-").takeWhile(Char::isLetter)
        val capitalizedHyphenTitle = after.startsWith("-") && hyphenWord.firstOrNull()?.isUpperCase() == true &&
            (hyphenWord.lowercase() in numberWords || hyphenWord.lowercase() in ordinals)
        val midSentenceCapital = firstCapital && !sentenceInitial && !allCaps
        val afterWords = after.split(cleaningRegex("\\s+")).filter(String::isNotEmpty)
        val modifiedUnit = next in setOf("square", "cubic") && afterWords.getOrNull(1)?.lowercase() in unitNouns
        val age = next in agePeriods && afterWords.getOrNull(1)?.equals("old", ignoreCase = true) == true
        val hyphenatedUnit = after.startsWith("-") && after.substringBefore(' ').split('-')
            .any { word -> word.lowercase() in unitNouns }
        val force = next in unitNouns || modifiedUnit || age || hyphenatedUnit ||
            cleaningRegex("(?i)^-(?:year|month|week|day)s?-old\\b").containsMatchIn(after)
        val replacement = if (value >= 10 || force) comma(value) else it.value
        when {
            (titleLike || capitalizedSingleTitle || capitalizedHyphenTitle) && !shout ||
                midSentenceCapital || allCaps && adjacentAllCaps && !shout -> it.value
            replacement == it.value -> it.value
            trailingAnd -> "$replacement and"
            else -> if (input.getOrNull(it.range.last + 1) in setOf('\n', '\r')) "$replacement " else replacement
        }
    }

    private fun keepMagnitude(input: String): String = cleaningRegex("(?:(?<=\\w[A-Z])|(?<![A-Z]))(\\$)?(\\d{1,3}(?:,\\d{3})+)\\b(?!\\.\\d)(?!,\\d)").replace(input) {
        val value = it.groupValues[2].replace(",", "").toLongOrNull() ?: return@replace it.value
        for ((word, scale) in listOf("trillion" to 1_000_000_000_000L, "billion" to 1_000_000_000L, "million" to 1_000_000L)) {
            val thousandth = scale / 1_000
            if (value >= scale && value % thousandth == 0L) {
                val coefficient = BigDecimal(value).divide(BigDecimal(scale)).stripTrailingZeros().toPlainString()
                if (coefficient.toBigDecimalOrNull()?.let { n -> n < BigDecimal(1_000) } == true) {
                    return@replace "${it.groupValues[1]}$coefficient $word"
                }
            }
        }
        it.value
    }

    private fun formatText(input: String): String {
        var value = input.replace(cleaningRegex("[ \\t]{2,}"), " ")
        value = value.replace(cleaningRegex("[ \\t]+([,;:?!.])"), "$1")
        value = value.replace(cleaningRegex("([.!?]\\s+)([\\p{Ll}])")) { "${it.groupValues[1]}${it.groupValues[2].uppercase()}" }
        return value.trim()
    }

    internal fun wordsToLong(raw: String): Long? {
        val tokens = raw.lowercase().replace("-", " ").trim().split(cleaningRegex("\\s+")).filter { it != "and" }
        if (tokens.isEmpty()) return null
        if (tokens.size == 1 && tokens[0] in setOf("zero", "0")) return 0
        var total = 0L
        var current = 0L
        var last = ""
        for (token in tokens) {
            val digit = token.replace(",", "").toLongOrNull()
            if (digit != null) {
                if (digit == 0L) return null
                current += digit
                last = if (digit < 10) "unit" else if (digit < 20) "teen" else "ten"
                continue
            }
            val unit = units[token]
            val ten = tens[token]
            when {
                unit != null -> {
                    if (unit == 0 || last in setOf("unit", "teen") || unit >= 10 && last == "ten") return null
                    current += unit
                    last = if (unit < 10) "unit" else "teen"
                }
                ten != null -> {
                    if (last in setOf("unit", "teen", "ten")) return null
                    current += ten
                    last = "ten"
                }
                token == "hundred" -> {
                    if (current in 1..99) current *= 100 else if (current == 0L) current = 100 else return null
                    last = "hundred"
                }
                scales[token] != null -> {
                    if (current == 0L && total == 0L) return null
                    total += (if (current == 0L) 1L else current) * scales.getValue(token)
                    current = 0
                    last = "scale"
                }
                else -> return null
            }
        }
        return total + current
    }

    internal fun spokenDigits(raw: String): String? {
        val output = StringBuilder()
        for (token in raw.trim().split(cleaningRegex("\\s+"))) {
            val digit = units[token.lowercase()]?.takeIf { it < 10 } ?: return null
            output.append(digit)
        }
        return output.toString()
    }

    internal fun parseYear(raw: String): Long? {
        val tokens = raw.lowercase().split(cleaningRegex("\\s+")).filter(String::isNotBlank)
        if (tokens.size < 2) return null
        if ("thousand" in tokens || "hundred" in tokens) return wordsToLong(raw)
        for (split in 1 until tokens.size) {
            val left = wordsToLong(tokens.take(split).joinToString(" "))
            val rightTokens = tokens.drop(split)
            val right = if (rightTokens.size == 2 && rightTokens.first() in setOf("oh", "o")) {
                units[rightTokens.last()]?.takeIf { it in 1..9 }?.toLong()
            } else {
                wordsToLong(rightTokens.joinToString(" "))
            }
            if (left != null && right != null && left in 10..99 && right in 0..99) return left * 100 + right
        }
        return wordsToLong(raw)
    }

    private fun comma(value: Long) = NumberFormat.getIntegerInstance(Locale.US).format(value)
    private fun ordinalSuffix(value: Int) = if (value % 100 in 11..13) "th" else when (value % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
}

internal object TextSafety {
    fun isDeterministicSafe(input: String, output: String, allowLargeContraction: Boolean): Boolean {
        if (input.isNotBlank() && output.isBlank()) return false
        if (output.any { it == '\u0000' || it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }) return false
        if (output.length > input.length * 3 + 200) return false
        return allowLargeContraction || input.length < 24 || output.length >= input.length / 4
    }

    fun isSafe(input: String, output: String, checkNumbers: Boolean = true): Boolean =
        refusal(input, output, checkNumbers) == null

    /**
     * Why the model's output is refused, or null when it is accepted. Six checks: the four this app has
     * always run, plus the Mac's word-count drop and question-to-answer rules
     * (`LLMPolishStep.validatePolishOutput`); the expansion rule is the Mac's max(3x, 200). A seventh,
     * [numbersMissing], is this app's own and applies to a MODEL's answer only: the spelling restore of
     * custom words passes `checkNumbers = false`, because it is not a model and may re-spell a number word.
     */
    fun refusal(input: String, output: String, checkNumbers: Boolean = true): String? {
        if (input.isNotBlank() && output.isBlank()) return "blank output"
        if (output.any { it == '\u0000' || it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }) return "control characters"
        if (output.length > maxOf(input.length * 3, 200)) return "expansion ${output.length}/${input.length} chars"
        if (input.length >= 24 && output.length < input.length / 4) return "contraction ${output.length}/${input.length} chars"
        val inputWords = input.split(cleaningRegex("\\s+")).count { it.isNotEmpty() }
        val outputWords = output.split(cleaningRegex("\\s+")).count { it.isNotEmpty() }
        if (inputWords >= 10 && outputWords < (inputWords * 2 + 4) / 5) return "content drop $outputWords/$inputWords words"
        if (looksLikeQuestion(input) && !looksLikeQuestion(output)) return "question turned into an answer"
        if (checkNumbers) {
            val missing = numbersMissing(input, output)
            if (missing > 0) return "number drop $missing numbers"
        }
        return null
    }

    private val numberWords = mapOf(
        "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L,
        "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L,
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
        "eighty" to 80L, "ninety" to 90L,
    )
    private val tensWords = numberWords.filterValues { it >= 20L }
    private val unitWords = mapOf(
        "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
        "eight" to 8L, "nine" to 9L,
    )
    private val outputNumberWords = numberWords + mapOf("one" to 1L, "zero" to 0L) + mapOf(
        // No "second" (also a unit of time) and no "dozen" (also "half a dozen", "two dozen"): a word that
        // can stand for a DIFFERENT quantity would hide a dropped tail, and a refusal only costs the polish.
        "first" to 1L, "third" to 3L, "fourth" to 4L, "fifth" to 5L, "sixth" to 6L,
        "seventh" to 7L, "eighth" to 8L, "ninth" to 9L, "tenth" to 10L, "eleventh" to 11L, "twelfth" to 12L,
        "thirteenth" to 13L, "fourteenth" to 14L, "fifteenth" to 15L, "sixteenth" to 16L,
        "seventeenth" to 17L, "eighteenth" to 18L, "nineteenth" to 19L, "twentieth" to 20L,
        "thirtieth" to 30L, "fortieth" to 40L, "fiftieth" to 50L, "sixtieth" to 60L, "seventieth" to 70L,
        "eightieth" to 80L, "ninetieth" to 90L,
        // Other words a model may write for a figure of a count.
        "nil" to 0L, "nought" to 0L, "naught" to 0L,
    )
    private val unitOrdinals = mapOf(
        "first" to 1L, "third" to 3L, "fourth" to 4L, "fifth" to 5L, "sixth" to 6L,
        "seventh" to 7L, "eighth" to 8L, "ninth" to 9L,
    )

    // One item of a spoken count: a figure of one or two digits, a number word, or "twenty one".
    private val countItem = run {
        val units = "one|two|three|four|five|six|seven|eight|nine"
        val teens = "ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen"
        val tens = "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
        "(?:(?:$tens)(?:[- ](?:$units))?|$teens|$units|zero|\\d{1,2})"
    }
    // A spoken count is three or more such items in a row joined by commas. Nothing else is checked: a lone
    // "1 time", "2nd" or "3:30" is a number a model may word another way, and refusing a correct answer
    // silently costs the polish, so only an enumeration (where a dropped tail is unmistakable) is held.
    private val spokenCount = cleaningRegex("(?<![\\p{L}\\d.:/$%#-])$countItem(?:,\\s*$countItem){2,}(?![\\p{L}\\d:/%-]|\\.\\d|,\\d|[\\s-]+(?:hundred|thousand|million|billion)|(?<=twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[\\s-]+(?:one|two|three|four|five|six|seven|eight|nine))")

    /**
     * How many counts in the input lost their last item in the model's output (#385: a twenty-item count
     * came back ending at seventeen). Only the LAST item of each count is held: both reproductions lost the
     * tail, and a model may legitimately fold the middle ("1, 2, 3" to "1 to 3") or use ordinals.
     *
     * The output must carry that value as many times as the INPUT does, in any form. A bare presence test
     * let an unrelated number hide the loss ("count to 20: 1, 2, ..., 20" answered "Count to 20: 1, ...,
     * 17" kept a 20), so every occurrence of the value in the input, inside the count or not, has to be
     * matched. A model that merges repeated values ("5, 5, 5" to "5") is refused: that costs the polish only.
     */
    internal fun numbersMissing(input: String, output: String): Int {
        val lowerInput = input.lowercase()
        val tails = spokenCount.findAll(lowerInput).mapNotNull { run ->
            cleaningRegex(countItem).findAll(run.value).lastOrNull()?.value
        }.toList()
        if (tails.isEmpty()) return 0
        val inputValues = numberValues(lowerInput)
        val lowerOutput = output.lowercase()
        val outputValues = numberValues(lowerOutput)
        return tails.distinct().count { item ->
            val tail = valueOfItem(item) ?: return@count false
            // "one" is not read as a number (it is also a pronoun), yet a count that ENDS on the spoken word
            // "one" ("three, two, one") must still be held: its own word then counts in the answer.
            val spelledOne = item == "one"
            val have = outputValues.count { it == tail } + if (spelledOne) oneWord.findAll(lowerOutput).count() else 0
            val needed = inputValues.count { it == tail } + if (spelledOne) oneWord.findAll(lowerInput).count() else 0
            have < maxOf(needed, 1)
        }
    }

    private val oneWord = cleaningRegex("(?<![\\p{L}\\d])one(?![\\p{L}\\d])")

    private fun valueOfItem(item: String): Long? {
        item.toLongOrNull()?.let { return it }
        val parts = item.split(' ', '-')
        return if (parts.size == 2) {
            val tens = tensWords[parts[0]] ?: return null
            val unit = unitWords[parts[1]] ?: return null
            tens + unit
        } else outputNumberWords[item]
    }

    // A figure glued to a letter ("v3", "a4") is an identifier, not a number: its digits never count.
    private val numberToken = cleaningRegex("(?<![\\p{L}\\d])(?:\\d{1,3}(?:,\\d{3})+|\\d+\\.\\d+|\\d+)(?![\\p{L}\\d])|\\p{L}+")
    private val scaleWords = setOf("hundred", "thousand", "million", "billion")

    /**
     * Every number in [lower], in order, each token consumed once: a figure (a thousands figure as one
     * value), a number word, and "twenty-one" as the single value 21, never as 20 and 1. A decimal is one
     * non-integer number and yields nothing, so "3.5" does not supply a 3 or a 5.
     */
    private fun numberValues(lower: String): List<Long> {
        val tokens = numberToken.findAll(lower).toList()
        val values = mutableListOf<Long>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val text = token.value
            index++
            // "one" is never read as a number: it is also a pronoun ("that's the one"), and a tail of 1
            // written that way is only a refusal, which costs the polish and nothing else.
            var value: Long? = when {
                text[0].isDigit() -> if ('.' !in text) text.replace(",", "").toLongOrNull() else null
                text == "one" -> null
                else -> outputNumberWords[text] ?: continue
            }
            var last = token
            val joined = tokens.getOrNull(index)
            if (value != null && tensWords[text] != null && joined != null && spacedOrHyphenated(lower, last, joined)) {
                val unit = unitWords[joined.value] ?: unitOrdinals[joined.value]
                if (unit != null) {
                    value += unit
                    last = joined
                    index++
                }
            }
            // A number that a scale word continues ("three hundred") is a different, larger number: it
            // supplies none of its parts.
            val scale = tokens.getOrNull(index)
            if (scale != null && scale.value in scaleWords && spacedOrHyphenated(lower, last, scale)) {
                index++
                value = null
            }
            value?.let { values += it }
        }
        return values
    }

    private fun spacedOrHyphenated(lower: String, from: MatchResult, to: MatchResult): Boolean {
        val gap = lower.substring(from.range.last + 1, to.range.first)
        return gap.isNotEmpty() && gap.all { it == ' ' || it == '-' }
    }

    private val leadingFillers = setOf("um", "uh", "so", "like", "well", "okay", "ok")
    // The Mac's twelve plus the plain auxiliaries it lacked ("was the meeting moved", "had they left"): code round 1.
    private val auxiliaryStarts = setOf(
        "am", "is", "are", "was", "were", "do", "does", "did", "has", "have", "had",
        "can", "could", "will", "would", "shall", "should", "may", "might", "must",
    )
    private val whWords = setOf("how", "what", "where", "when", "who", "why")
    private val whFollowers = setOf("many", "much", "long", "often")
    private val indirectPreambles = listOf("i was wondering if", "i'm wondering if", "wondering if", "whether we should", "do you know if", "is there a", "are we")

    /** The Mac's conservative question detector: a `?`, or after leading fillers a strong interrogative start. */
    fun looksLikeQuestion(text: String): Boolean {
        if (text.contains('?')) return true
        // Tokens shed every boundary mark, quotes and apostrophes included (an internal apostrophe, "i'm", stays),
        // so a quoted start still matches.
        val words = text.lowercase().trim().split(cleaningRegex("\\s+"))
            .map { token -> token.trim { !it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .toMutableList()
        while (words.isNotEmpty() && words.first() in leadingFillers) words.removeAt(0)
        val first = words.firstOrNull() ?: return false
        if (first in auxiliaryStarts) return true
        if (first in whWords) {
            val second = words.getOrNull(1) ?: ""
            if (second in auxiliaryStarts || second in whFollowers) return true
        }
        val joined = words.take(5).joinToString(" ")
        return indirectPreambles.any { joined.startsWith(it) }
    }
}
