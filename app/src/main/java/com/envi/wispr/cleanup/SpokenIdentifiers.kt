package com.envi.wispr.cleanup

import java.time.DateTimeException
import java.time.LocalDate

/** Whole identifier reads, before the older decimal/cardinal passes can consume a prefix. */
internal object SpokenIdentifiers {
    private val englishDots = setOf("point", "dot")
    private val foreignDots = setOf("punkt", "punto", "ponto")
    private val englishDashes = setOf("dash", "hyphen")
    private val foreignDashes = setOf("strich", "bindestrich", "tiret", "guion", "guión", "trattino", "traço", "hífen", "hifen")
    private val dutchDashes = CleanupReferenceTables.dutchDashWords
    private val neutralDashes = foreignDashes + englishDashes + CleanupReferenceTables.neutralOnlyDashWords
    private val dutchDigits = listOf("nul", "één", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen").withIndex().associate { it.value to it.index }
    private val wordAlt = alt(DeterministicCleanup.units.keys + DeterministicCleanup.tens.keys + setOf("hundred", "thousand"))
    private val part = "(?:\\d+|(?:$wordAlt)(?:\\s+(?:$wordAlt))*)"
    private val refusedLeads = setOf("at", "to", "in", "on", "by", "for", "of", "and", "or", "but", "the", "a", "an", "from", "with", "about", "after", "before", "around", "until", "since", "so", "then", "when", "if", "we", "i", "it", "he", "she", "they", "you", "there", "that", "this")

    fun alt(words: Collection<String>): String = words.sortedWith(compareByDescending<String> { it.length }.thenBy { it }).joinToString("|") { it.split(' ').joinToString("\\s+") { word -> word.split('\'').joinToString("['’]") { part -> Regex.escape(part) } } }
    fun digits(raw: String, allowWords: Boolean): String? {
        val words = raw.trim().lowercase().split(cleaningRegex("\\s+"))
        if (words.size == 1 && words[0].matches(cleaningRegex("\\d{1,9}"))) return words[0]
        if (!allowWords) return null
        if (words.all { (DeterministicCleanup.units[it] ?: 10) < 10 }) return words.joinToString("") { DeterministicCleanup.units.getValue(it).toString() }
        return DeterministicCleanup.wordsToLong(raw)?.toString()
    }

    private fun continues(text: String, m: MatchResult, connectors: String): Boolean {
        val before = text.substring(maxOf(0, m.range.first - 24), m.range.first)
        val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 25))
        return cleaningRegex("(?:^|\\s)(?:$connectors)\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before) ||
            cleaningRegex("^\\s+(?:$connectors)\\s+\\S", RegexOption.IGNORE_CASE).containsMatchIn(after) ||
            (m.value.last().isDigit() && !cleaningRegex("\\d{2,}$").containsMatchIn(m.value) && cleaningRegex("^\\s+\\d(?=\\s|$)").containsMatchIn(after))
    }

    fun normalize(input: String, english: Boolean): String {
        var text = dotted(input, english)
        if (english) text = minorVersions(text)
        text = dashedDates(text, english)
        text = dashedCodes(text, english)
        if (english) text = ports(text)
        return text
    }

    private fun dotted(text: String, english: Boolean): String {
        val p = if (english) part else "\\d+"
        val dots = alt(englishDots + foreignDots + if (english) emptySet() else setOf("kropka", "punt", "точка"))
        val gap = if (english) "\\s+" else "[^\\S\\r\\n]+"
        val sep = "$gap(?:$dots)$gap"
        val re = cleaningRegex("(?<![\\w.])(?:\\d+(?:\\.\\d+)+(?:$sep$p)+|$p(?:$sep$p){2,})(?![\\w]|\\.\\d)", RegexOption.IGNORE_CASE)
        return re.replace(text) { m ->
            val before = text.substring(maxOf(0, m.range.first - 24), m.range.first)
            if (continues(text, m, "$dots|double|triple") || cleaningRegex("(?:^|\\s)(?:$wordAlt|(?:hundred|thousand)\\s+and|\\d+)\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before) || (!english && (NeutralAddresses.inUnfinishedLink(text, m.range.first) || NeutralLinks.identifierContinues(text, m.range.last + 1)))) m.value
            else {
                val allow = cleaningRegex("$gap($dots)$gap", RegexOption.IGNORE_CASE).findAll(m.value).all { it.groupValues[1].lowercase() in englishDots }
                val pieces = m.value.split(cleaningRegex(sep, RegexOption.IGNORE_CASE))
                val result = pieces.mapIndexed { index, piece -> if (index == 0 && '.' in piece) piece else digits(piece, allow) }
                if (result.any { it == null }) m.value else result.joinToString(".")
            }
        }
    }

    private fun minorVersions(text: String): String {
        val minor = "(?:\\d{1,2}|(?:${alt(DeterministicCleanup.tens.keys)})(?:\\s+(?:${alt(DeterministicCleanup.units.filterValues { it in 1..9 }.keys)}))?|${alt(DeterministicCleanup.units.filterValues { it in 10..19 }.keys)})"
        val pattern = "(?<lead>\\b(?:version|v|release|build|update|[A-Z][A-Za-z]*|[a-z]+[A-Z][A-Za-z]*))\\s+(?<major>(?iu:$part))\\s+(?iu:point)\\s+(?<minor>(?iu:$minor))(?=[\\s.,;:!?)\\]”\"']|$)"
        return cleaningRegex(pattern).replace(text) { m ->
            val lead = m.groups["lead"]!!.value
            val major = digits(m.groups["major"]!!.value, true)
            val rawMinor = m.groups["minor"]!!.value
            val value = rawMinor.toIntOrNull() ?: DeterministicCleanup.wordsToLong(rawMinor)
            if (lead.lowercase() in refusedLeads || continues(text, m, alt(englishDots + foreignDots)) || major == null || value == null) m.value else "$lead $major.$value"
        }
    }

    private fun dashedCodes(text: String, english: Boolean): String {
        val dash = alt(if (english) englishDashes + foreignDashes else neutralDashes)
        val number = if (english) "$part(?:(?<=hundred|thousand)\\s+and\\s+$part)*" else "\\d+|${alt(dutchDigits.keys)}"
        val glued = if (english) "" else "|(?<gcode>[A-Z]{1,5}|[b-hj-z])-(?<gdw>(?iu:$dash))\\s+"
        val re = cleaningRegex("(?<![\\w/-])(?:(?<code>(?:[A-Z][ \\t]+){0,3}[A-Z]{1,5}|[b-hj-z])\\s+(?<dw>(?iu:$dash))\\s+|(?<hcode>[A-Z]{1,5}|[a-z])-[ \\t]+$glued)(?<num>(?iu:$number))(?![\\w-])")
        return re.replace(text) { m ->
            val hyphenCode = m.groups["hcode"]?.value
            val raw = hyphenCode ?: (if (english) null else m.groups["gcode"]?.value) ?: m.groups["code"]!!.value.filterNot(Char::isWhitespace)
            val before = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            val article = cleaningRegex("(?:^|\\s)[Aa]\\s+$").containsMatchIn(before) && !cleaningRegex("(?:^|\\s)[A-Za-z]\\s+[Aa]\\s+$").containsMatchIn(before)
            val code = if (raw.length == 1) raw.uppercase() else raw
            val dw = (m.groups["dw"]?.value ?: (if (english) null else m.groups["gdw"]?.value) ?: "").lowercase().replace(cleaningRegex("\\s+"), " ")
            val rawNumber = m.groups["num"]!!.value.lowercase()
            val dutch = if (!english && dw in dutchDashes) dutchDigits[rawNumber]?.toString() else null
            val value = dutch ?: digits(rawNumber, english && (hyphenCode != null || dw in englishDashes))
            if ((raw.length == 1 && raw == raw.lowercase() && cleaningRegex("(?:^|\\s)[A-Za-z]\\s+$").containsMatchIn(before) && !article) || code == "I" || hyphenCode?.lowercase() == "a" || hyphenCode == "i" || continues(text, m, dash) || (!english && NeutralAddresses.inUnfinishedLink(text, m.range.first)) || value == null) m.value else "$code-$value"
        }
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean = try {
        LocalDate.of(year, month, day)
        true
    } catch (_: DateTimeException) { false }

    private fun dashedDates(input: String, english: Boolean): String {
        var text = input
        if (english) {
            val yearWords = "(?:$wordAlt)(?:\\s+(?:$wordAlt)){1,3}"
            val re = cleaningRegex("(?<![\\w-])(?<y>\\d{4}|$yearWords)\\s+(?:dash|hyphen)\\s+(?<mon>$part)\\s+(?:dash|hyphen)\\s+(?<day>$part)(?![\\w-])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val rawYear = m.groups["y"]!!.value
                val year = rawYear.toIntOrNull() ?: DeterministicCleanup.parseYear(rawYear)?.toInt()
                val month = digits(m.groups["mon"]!!.value, true)?.toIntOrNull()
                val day = digits(m.groups["day"]!!.value, true)?.toIntOrNull()
                if (continues(text, m, "dash|hyphen") || year == null || year !in 1000..2999 || month == null || day == null || !validDate(year, month, day)) m.value else "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, month, day)
            }
        }
        return cleaningRegex("(?<![\\w./=#?&:@-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\w@/-]|\\.\\w)").replace(text) { m ->
            val year = m.groupValues[1].toInt(); val month = m.groupValues[2].toInt(); val day = m.groupValues[3].toInt()
            if (continues(text, m, alt(englishDashes + foreignDashes)) || !validDate(year, month, day) || (m.groupValues[2].length == 2 && m.groupValues[3].length == 2)) m.value else "%s-%02d-%02d".format(java.util.Locale.ROOT, m.groupValues[1], month, day)
        }
    }

    private fun ports(text: String): String = cleaningRegex("\\b(?<host>localhost)\\s+colon\\s+(?<port>(?:$wordAlt)(?:\\s+(?:$wordAlt)|(?<=hundred|thousand)\\s+and)*|\\d[\\d,]*)(?![\\w])", RegexOption.IGNORE_CASE).replace(text) { m ->
        val raw = m.groups["port"]!!.value.replace(",", "")
        val port = raw.toLongOrNull() ?: DeterministicCleanup.spokenDigits(raw)?.toLongOrNull() ?: DeterministicCleanup.wordsToLong(raw)
        if (port == null || port !in 1..65535) m.value else "${m.groups["host"]!!.value}:$port"
    }
}
