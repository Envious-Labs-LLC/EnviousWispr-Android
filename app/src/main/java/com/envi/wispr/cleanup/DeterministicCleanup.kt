package com.envi.wispr.cleanup

import java.text.NumberFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class CleanupOptions(
    val removeFillers: Boolean = true,
    val spokenEmoji: Boolean = true,
    val spokenPunctuation: Boolean = false,
)

data class CleanupResult(val text: String, val changed: Boolean, val recovered: Boolean)

/** Conservative, deterministic English cleanup. It never calls a network service. */
object DeterministicCleanup {
    // `um` and `err` were removed 2026-09-02 (#36, #107). Both are ordinary WORDS, so stripping them
    // deletes something the speaker authored, and RULE: matcher-set-adversarial-tests says that direction
    // fails worse than leaving a filler in. `err` is an English verb: "To err is human" became "To is
    // human". `um` is a German preposition, a Portuguese article, and a Croatian and Slovenian noun, all
    // inside the 25 languages Parakeet v3 decodes. The remaining six are not words in those languages.
    private val filler = Regex("\\b(uh|erm|ah|hmm|hm|mhm)\\b[,:]?\\s*", RegexOption.IGNORE_CASE)
    private val emoji = linkedMapOf("smiley face" to "🙂", "smiling face" to "🙂", "thumbs up" to "👍", "heart" to "❤️", "fire" to "🔥")
    private val punctuation = linkedMapOf(
        "new paragraph" to "\n\n", "new line" to "\n", "question mark" to "?",
        "exclamation mark" to "!", "exclamation point" to "!", "full stop" to ".",
        "semicolon" to ";", "period" to ".", "comma" to ",", "colon" to ":",
    )
    private val protectedPhrases = listOf(
        Regex("\\beleventh hour\\b", RegexOption.IGNORE_CASE),
        Regex("\\bthe whole nine yards\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:a |an )?(?:quarter|half)\\s+(?:past|to)\\s+\\w+", RegexOption.IGNORE_CASE),
        Regex("\\b(?:a |an )?(?:couple|few|several|many)\\s+hundred\\b", RegexOption.IGNORE_CASE),
    )
    private val units = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "one" to 1, "two" to 2, "three" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val scales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val numberWords = units.keys + tens.keys + scales.keys + "and"
    private val numberAlt = numberWords.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val numberNoAndAlt = numberWords.filterNot { it == "and" }.sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) }
    private val numberRun = "(?:$numberAlt|\\d[\\d,]*)(?:[ -]+(?:$numberAlt|\\d[\\d,]*))*"
    private val numberRunNoLeadingAnd =
        "(?:$numberNoAndAlt|\\d[\\d,]*)(?:[ -]+(?:$numberAlt|\\d[\\d,]*))*"
    private val digitAlt = units.filterValues { it < 10 }.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val dottedNumericChain = Regex(
        "(?i)\\b(?:$digitAlt|\\d[\\d,]*)(?:\\s+(?:$digitAlt|\\d[\\d,]*))*" +
            "(?:\\s+dot\\s+(?:$digitAlt|\\d[\\d,]*)(?:\\s+(?:$digitAlt|\\d[\\d,]*))*){2,}\\b",
    )
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

    fun apply(raw: String, options: CleanupOptions = CleanupOptions()): CleanupResult {
        if (raw.isBlank()) return CleanupResult(raw, false, false)
        val original = raw.trim()
        return try {
            var value = original
            if (options.removeFillers) {
                value = filler.replace(value, "")
            }
            if (options.spokenEmoji) emoji.forEach { (phrase, symbol) ->
                val discussion = "category|categories|feature|features|name|names|symbol|symbols|" +
                    "word|words|button|buttons|glyph|glyphs|icon|icons|character|characters|" +
                    "version|format|library|set|picker|keyboard|meaning|description|usage|" +
                    "shortcode|unicode|code"
                value = value.replace(
                    Regex(
                        "\\b${Regex.escape(phrase)}\\s+(?:emoji|emoticon)\\b" +
                            "(?!\\s+(?:$discussion)\\b)",
                        RegexOption.IGNORE_CASE,
                    ),
                    symbol,
                )
            }
            val protected = mutableListOf<String>()
            (protectedPhrases + dottedNumericChain).forEach { phrase ->
                value = phrase.replace(value) { match ->
                    protected += match.value
                    "\uE000${protected.lastIndex}\uE001"
                }
            }
            value = Regex("\\b(?:a|an)\\s+(hundred\\b)(?!-)").replace(value, "$1")
            value = Regex(
                "(?i)\\b(a|an|the|this|that|another)\\s+catch[\\s-]+(?:twenty[\\s-]+two|22)\\b",
            ).replace(value) { "${it.groupValues[1]} Catch-22" }
            val beforeStructured = value
            value = normalizeStructured(value)
            val structuredChanged = value != beforeStructured
            protected.forEachIndexed { index, phrase -> value = value.replace("\uE000$index\uE001", phrase) }
            if (options.spokenPunctuation) punctuation.forEach { (phrase, mark) ->
                val command = if ('\n' in mark) {
                    Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
                } else {
                    Regex("\\s*\\b${Regex.escape(phrase)}\\b\\s*", RegexOption.IGNORE_CASE)
                }
                val replacement = if ('\n' in mark) mark else "$mark "
                value = value.replace(command, replacement)
            }
            value = formatText(value)
            if (!TextSafety.isDeterministicSafe(original, value, structuredChanged)) CleanupResult(original, false, true)
            else CleanupResult(value, value != original, false)
        } catch (_: RuntimeException) {
            CleanupResult(original, false, true)
        }
    }

    private fun normalizeStructured(input: String): String {
        var hyphenated = input
        val hyphenJoin = Regex("\\b((?i:$numberNoAndAlt))-(?=(?:$numberNoAndAlt|$ordinalAlt)\\b)")
        do {
            val previous = hyphenated
            hyphenated = hyphenJoin.replace(hyphenated, "$1 ")
        } while (hyphenated != previous)
        var text = " $hyphenated "
        text = emails(text)
        text = urls(text)
        text = decimals(text)
        text = moneyPercent(text)
        text = times(text)
        text = dates(text)
        text = ordinalNumbers(text)
        text = years(text)
        text = moneyPercent(text)
        text = digitRuns(text)
        text = digitScales(text)
        text = rangesAndDimensions(text)
        text = dosageRuns(text)
        text = cardinals(text)
        text = keepMagnitude(text)
        return text.trim()
    }

    private fun emails(input: String) = Regex(
        "\\b([a-z][a-z0-9_.+-]*)\\s+at\\s+([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\\s+dot\\s+(com|org|io|co|dev|me|net|edu|gov)\\b",
        RegexOption.IGNORE_CASE,
    ).replace(input) { "${it.groupValues[1]}@${it.groupValues[2]}.${it.groupValues[3]}" }

    private fun urls(input: String): String {
        val tlds = "com|org|io|co|dev|me|net"
        var text = input.replace(Regex("(?i)\\b(?:h|aitch)\\s+slash\\s+slash(?=\\s+[a-z0-9])"), "https slash slash")
        text = Regex(
            "(?<![@\\w.-])([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)\\s+dot\\s+($tlds)((?:\\s+slash\\s+[a-z0-9-]+)*)",
            RegexOption.IGNORE_CASE,
        ).replace(text) {
            if (hasUnresolvedUrlContext(text, it)) it.value
            else it.groupValues[1] + "." + it.groupValues[2] + Regex("(?i)\\s+slash\\s+").replace(it.groupValues[3], "/")
        }
        text = Regex(
            "(?<![@\\w.-])([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.(?:$tlds|ai|app|xyz))((?:\\s+slash\\s+[a-z0-9-]+)+)",
            RegexOption.IGNORE_CASE,
        ).replace(text) {
            if (hasUnresolvedUrlContext(text, it)) it.value
            else it.groupValues[1] + Regex("(?i)\\s+slash\\s+").replace(it.groupValues[2], "/")
        }
        return text.replace(Regex("(?i)(\\b[a-z0-9][a-z0-9.-]*\\.(?:$tlds|ai|app|xyz))\\s+dot\\.?\\s*$"), "$1")
    }

    private fun hasUnresolvedUrlContext(text: String, match: MatchResult): Boolean {
        val before = text.substring(0, match.range.first).trimEnd().lowercase()
        val after = text.substring(match.range.last + 1).trimStart().lowercase()
        val beforeWords = before.split(Regex("\\s+")).filter(String::isNotEmpty)
        val emailLikeAt = beforeWords.size <= 2 && beforeWords.lastOrNull() == "at"
        val blockedBefore = emailLikeAt || listOf("@", "slash slash", " dot", " dash").any(before::endsWith)
        val blockedAfter = Regex("^(?:slash|dot|underscore|dash|question mark|equals?|colon|at)\\b")
            .containsMatchIn(after)
        val letterSpelledHost = beforeWords.takeLast(3).size == 3 &&
            beforeWords.takeLast(3).all { word -> word.length == 1 } &&
            Regex("^dot(?:\\s+[a-z]){2,}\\b", RegexOption.IGNORE_CASE).containsMatchIn(after)
        return blockedBefore || blockedAfter && !letterSpelledHost
    }

    private fun decimals(input: String): String {
        var text = Regex("(?i)\\b($numberRun)\\s+(?:point|dot)\\s+((?:$digitAlt)(?:\\s+(?:$digitAlt))*)(?:\\s+(thousand|million|billion))?\\b").replace(input) { match ->
            val whole = wordsToLong(match.groupValues[1]) ?: return@replace match.value
            val digits = spokenDigits(match.groupValues[2]) ?: return@replace match.value
            val scale = scales[match.groupValues[3].lowercase()]
            if (scale == null) "$whole.$digits" else runCatching {
                BigDecimal("$whole.$digits").multiply(BigDecimal(scale))
                    .setScale(0, RoundingMode.HALF_EVEN).longValueExact().let(::comma)
            }.getOrElse { match.value }
        }
        return Regex("(?i)\\b(?:(negative|minus)\\s+)?point\\s+((?:$digitAlt)(?:\\s+(?:$digitAlt))*)\\b").replace(text) {
            val digits = spokenDigits(it.groupValues[2]) ?: return@replace it.value
            if (it.groupValues[1].isBlank() && digits.length < 3) it.value else "${if (it.groupValues[1].isBlank()) "" else "negative "}0.$digits"
        }
    }

    private fun moneyPercent(input: String): String {
        var text = Regex("(?i)(?<![\\d.])\\b($numberRun)\\s+dollars?\\s+($numberRunNoLeadingAnd)\\s+cents?\\b").replace(input) {
            val dollars = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val cents = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            "$${comma(dollars)} $${"%.2f".format(Locale.US, cents / 100.0)}"
        }
        text = Regex("(?i)(?<![\\d.])\\b($numberRun)\\s+dollars?(?:\\s+and\\s+($numberRun)\\s+cents?)?\\b").replace(text) {
            val dollars = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (it.groupValues[2].isBlank()) "$${comma(dollars)}" else {
                val cents = wordsToLong(it.groupValues[2]) ?: return@replace it.value
                "$${comma(dollars)}.${cents.toString().padStart(2, '0')}"
            }
        }
        text = Regex("(?i)(?<![\\d.])\\b($numberRun)\\s+cents?\\b").replace(text) {
            val cents = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            "$${"%.2f".format(Locale.US, cents / 100.0)}"
        }
        text = Regex("(?i)\\b($numberRun)\\s+(?:percent|per\\s+cent)\\b").replace(text) {
            val value = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            "$value%"
        }
        return Regex("(?i)\\b(\\d[\\d,]*\\.\\d+)\\s+(?:percent|per\\s+cent)\\b").replace(text, "$1%")
    }

    private fun times(input: String): String {
        val hourToken = "(?:$numberNoAndAlt|\\d{1,2})"
        var text = Regex("(?i)\\b(?:oh|o)\\s+($digitAlt)\\s+hundred\\b").replace(input) {
            val hour = units[it.groupValues[1].lowercase()] ?: return@replace it.value
            "${hour}00"
        }
        text = Regex("(?i)(?<![:\\d])\\b($hourToken)(?:\\s+($numberRun))?\\s+([ap])\\s*m\\b").replace(text) {
            val hour = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val minute = if (it.groupValues[2].isBlank()) 0 else wordsToLong(it.groupValues[2]) ?: spokenDigits(it.groupValues[2])?.toLongOrNull() ?: return@replace it.value
            if (hour !in 1..12 || minute !in 0..59) it.value else "$hour:${minute.toString().padStart(2, '0')} ${it.groupValues[3].uppercase()}M"
        }
        return Regex("(?i)\\b($numberRun)\\s+o'?clock\\b").replace(text) {
            val hour = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (hour in 1..12) "$hour:00" else it.value
        }
    }

    private fun dates(input: String): String {
        val monthAlt = months.keys.joinToString("|")
        return Regex("(?i)\\b($monthAlt)\\s+($dateOrdinalAlt|\\d{1,2}),?\\s+($numberRun)\\b").replace(input) {
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
        var text = Regex(
            "(?i)\\btwo thousand(?:\\s+and)?\\s+($twoThousandLow)\\b" +
                "(?!\\s+(?:hundred|thousand|million|billion))",
        ).replace(input) {
            val low = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            if (low in 1..99) "${2000 + low}" else it.value
        }
        text = Regex("(?i)\\b($centuries)\\s+($centuryLow)\\b").replace(text) {
            val century = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val lowRaw = it.groupValues[2]
            val low = if (Regex("(?i)^(oh|o)\\s+").containsMatchIn(lowRaw)) units[lowRaw.trim().split(Regex("\\s+")).last().lowercase()]?.toLong() else wordsToLong(lowRaw)
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
        var text = Regex("(?i)\\b(?:($numberRun)\\s+)?($scaleOrdinalAlt)\\b").replace(input) {
            val scaleWord = scaleOrdinals.getValue(it.groupValues[2].lowercase())
            val cardinal = listOf(it.groupValues[1], scaleWord).filter(String::isNotBlank).joinToString(" ")
            val value = wordsToLong(cardinal) ?: return@replace it.value
            val after = input.substring(it.range.last + 1).trimStart()
            if (after.startsWith("of ", ignoreCase = true)) it.value else "${comma(value)}${ordinalSuffix(value.toInt())}"
        }
        text = Regex("(?i)\\b($numberRun)\\s+($ordinalAlt)\\b").replace(text) {
            val tailWord = it.groupValues[2].lowercase()
            val after = text.substring(it.range.last + 1).trimStart()
            if (tailWord == "second" && durationNoun(after)) return@replace it.value
            if (after.startsWith("of ", ignoreCase = true)) return@replace it.value
            val base = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val lastCardinal = it.groupValues[1].lowercase().split(Regex("\\s+"))
                .lastOrNull { word -> word != "and" }
            if (lastCardinal !in scales) return@replace it.value
            val value = base + ordinals.getValue(tailWord)
            "${comma(value)}${ordinalSuffix(value.toInt())}"
        }
        val unitOrdinal = ordinals.filterValues { it in 1..9 }.keys.joinToString("|")
        text = Regex("(?i)\\b(${tens.keys.joinToString("|")})\\s+($unitOrdinal)\\b").replace(text) {
            val tailWord = it.groupValues[2].lowercase()
            val after = text.substring(it.range.last + 1).trimStart()
            if (tailWord == "second" && durationNoun(after)) return@replace it.value
            val value = tens.getValue(it.groupValues[1].lowercase()) + ordinals.getValue(tailWord)
            "$value${ordinalSuffix(value)}"
        }
        return Regex("(?i)\\b($ordinalAlt)\\b").replace(text) {
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

    private fun digitRuns(input: String): String = Regex("(?i)\\b(?:$digitAlt|\\d{1,4})(?:\\s+(?:$digitAlt|\\d{1,4})){1,}\\b").replace(input) {
        val tokens = it.value.split(Regex("\\s+"))
        val digits = tokens.joinToString("") { token -> units[token.lowercase()]?.takeIf { n -> n < 10 }?.toString() ?: token }
        when {
            digits.length == 10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.takeLast(4)}"
            digits.length == 7 -> "${digits.take(3)}-${digits.takeLast(4)}"
            tokens.any { token -> token.lowercase() in setOf("zero", "oh", "o") } && digits.length <= 6 -> digits
            else -> it.value
        }
    }

    private fun rangesAndDimensions(input: String): String {
        var text = Regex("(?i)\\bbetween\\s+($numberRun)\\s+and\\s+($numberRun)\\b").replace(input) {
            val a = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val b = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            if (it.groupValues[1].contains(Regex("(?i)\\band\\b"))) {
                "between ${comma(a)} and ${comma(b)}"
            } else {
                "between ${comma(a)}-${comma(b)}"
            }
        }
        text = Regex("(?i)\\b($numberRun)\\s+(?:to|through)\\s+($numberRun)\\b").replace(text) {
            val a = wordsToLong(it.groupValues[1]) ?: return@replace it.value
            val b = wordsToLong(it.groupValues[2]) ?: return@replace it.value
            "${comma(a)}-${comma(b)}"
        }
        text = Regex("(?i)(?<![\\d.])\\b($numberRun)(?:\\s+slash\\s+$numberRun)+\\b").replace(text) {
            val parts = it.value.split(Regex("(?i)\\s+slash\\s+"))
            val values = parts.map(::wordsToLong)
            if (values.any { value -> value == null }) it.value else values.joinToString("/") { value -> value.toString() }
        }
        text = Regex("(?i)(?<![\\d.])\\b($numberRun)(?:\\s+by\\s+$numberRun)+\\b").replace(text) {
            val parts = it.value.split(Regex("(?i)\\s+by\\s+"))
            val values = parts.map(::wordsToLong)
            val after = text.substring(it.range.last + 1).trimStart()
            val followedByUnit = after.takeWhile(Char::isLetter).lowercase() in unitNouns
            if (values.any { value -> value == null } || values.all { value -> value == 1L } && !followedByUnit) {
                it.value
            } else {
                values.joinToString(" by ") { value -> comma(value!!) }
            }
        }
        return Regex("(?<![-\\d.])\\b(\\d+)\\s+to\\s+(\\d+)\\b").replace(text, "$1-$2")
    }

    private fun digitScales(input: String): String = Regex(
        "(?i)\\b(\\d[\\d,]*\\s+(?:hundred|thousand)(?:\\s+(?:$numberAlt))*)\\b",
    ).replace(input) {
        wordsToLong(it.groupValues[1])?.let(::comma) ?: it.value
    }

    private fun dosageRuns(input: String): String {
        val oneToNine = units.filterValues { it in 1..9 }.keys.joinToString("|")
        val dosageUnits = "milligram|milligrams|mg|milliliter|milliliters|ml"
        return Regex("(?i)\\b($numberNoAndAlt)\\s+($oneToNine)(?=\\s+(?:$dosageUnits)\\b)").replace(input) {
            val digit = units[it.groupValues[2].lowercase()] ?: return@replace it.value
            "${it.groupValues[1]} $digit"
        }
    }

    private fun cardinals(input: String): String = Regex("(?i)\\b(?:$numberNoAndAlt)(?:\\s+(?:$numberAlt))*\\b").replace(input) {
        val words = it.value.trim().split(Regex("\\s+")).toMutableList()
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
        val afterWords = after.split(Regex("\\s+")).filter(String::isNotEmpty)
        val modifiedUnit = next in setOf("square", "cubic") && afterWords.getOrNull(1)?.lowercase() in unitNouns
        val age = next in agePeriods && afterWords.getOrNull(1)?.equals("old", ignoreCase = true) == true
        val hyphenatedUnit = after.startsWith("-") && after.substringBefore(' ').split('-')
            .any { word -> word.lowercase() in unitNouns }
        val force = next in unitNouns || modifiedUnit || age || hyphenatedUnit ||
            Regex("(?i)^-(?:year|month|week|day)s?-old\\b").containsMatchIn(after)
        val replacement = if (value >= 10 || force) comma(value) else it.value
        when {
            (titleLike || capitalizedSingleTitle || capitalizedHyphenTitle) && !shout ||
                midSentenceCapital || allCaps && adjacentAllCaps && !shout -> it.value
            replacement == it.value -> it.value
            trailingAnd -> "$replacement and"
            else -> replacement
        }
    }

    private fun keepMagnitude(input: String): String = Regex("(\\$)?(\\d{1,3}(?:,\\d{3})+)(?!\\.\\d)(?!,\\d)").replace(input) {
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
        var value = input.replace(Regex("[ \\t]{2,}"), " ")
        value = value.replace(Regex("[ \\t]+([,;:?!.])"), "$1")
        value = value.replace(Regex("([.!?]\\s+)([\\p{Ll}])")) { "${it.groupValues[1]}${it.groupValues[2].uppercase()}" }
        return value.trim()
    }

    private fun wordsToLong(raw: String): Long? {
        val tokens = raw.lowercase().replace("-", " ").trim().split(Regex("\\s+")).filter { it != "and" }
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

    private fun spokenDigits(raw: String): String? {
        val output = StringBuilder()
        for (token in raw.trim().split(Regex("\\s+"))) {
            val digit = units[token.lowercase()]?.takeIf { it < 10 } ?: return null
            output.append(digit)
        }
        return output.toString()
    }

    private fun parseYear(raw: String): Long? {
        val tokens = raw.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
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

object TextSafety {
    fun isDeterministicSafe(input: String, output: String, allowLargeContraction: Boolean): Boolean {
        if (input.isNotBlank() && output.isBlank()) return false
        if (output.any { it == '\u0000' || it.isISOControl() && it != '\n' && it != '\t' }) return false
        if (output.length > input.length * 3 + 200) return false
        return allowLargeContraction || input.length < 24 || output.length >= input.length / 4
    }

    fun isSafe(input: String, output: String): Boolean = refusal(input, output) == null

    /**
     * Why the model's output is refused, or null when it is accepted. Six checks: the four this app has
     * always run, plus the Mac's word-count drop and question-to-answer rules
     * (`LLMPolishStep.validatePolishOutput`); the expansion rule is the Mac's max(3x, 200).
     */
    fun refusal(input: String, output: String): String? {
        if (input.isNotBlank() && output.isBlank()) return "blank output"
        if (output.any { it == '\u0000' || it.isISOControl() && it != '\n' && it != '\t' }) return "control characters"
        if (output.length > maxOf(input.length * 3, 200)) return "expansion ${output.length}/${input.length} chars"
        if (input.length >= 24 && output.length < input.length / 4) return "contraction ${output.length}/${input.length} chars"
        val inputWords = input.split(Regex("\\s+")).count { it.isNotEmpty() }
        val outputWords = output.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (inputWords >= 10 && outputWords < (inputWords * 2 + 4) / 5) return "content drop $outputWords/$inputWords words"
        if (looksLikeQuestion(input) && !looksLikeQuestion(output)) return "question turned into an answer"
        return null
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
        val words = text.lowercase().trim().split(Regex("\\s+"))
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
