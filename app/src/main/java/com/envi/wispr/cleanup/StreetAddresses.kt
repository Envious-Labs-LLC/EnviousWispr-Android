package com.envi.wispr.cleanup

/** Complete US addresses only; never guess a missing state, ZIP or street/city split. */
internal object StreetAddresses {
    private val t = CleanupReferenceTables
    private val hs = "[\\t\\p{Zs}]+"
    private val cap = "[A-Z][\\p{L}'’-]*"
    private val numWord = "(?i:${SpokenIdentifiers.alt(DeterministicCleanup.units.keys + DeterministicCleanup.tens.keys)}|hundred|thousand)"
    private val digitWord = "(?i:zero|oh|o|one|two|three|four|five|six|seven|eight|nine)"
    private val separator = "((?>[^\\S\\n]*,[^\\S\\n]*\\n?[^\\S\\n]*|[^\\S\\n]*\\n[^\\S\\n]*|[^\\S\\n]+))"
    private val pattern = run {
        val house = "(\\d{1,6}|$numWord(?:$hs(?:(?i:and)$hs)?$numWord){0,5})"
        val street = "((?:(?:$cap|\\d{1,3}(?:st|nd|rd|th))$hs){1,4}(?:${SpokenIdentifiers.alt(t.streetTypes)}))"
        val unitNumber = "(?:\\d{1,5}(?:[A-Z]|$hs[A-Z])?(?![\\p{L}\\d])|$numWord(?:$hs(?:(?i:and)$hs)?$numWord){0,5}(?:$hs[A-Z](?![\\p{L}]))?)"
        val unit = "(?:((?:${SpokenIdentifiers.alt(t.streetUnitWords)})\\.?$hs$unitNumber|#\\s?\\d{1,5}[A-Z]?)$separator)?"
        val cityCap = "(?:(?:St|Ft|Mt)\\.|$cap)"
        val city = "($cityCap(?:$hs(?:(?:of|on|upon|de|del|la|le|du|the)$hs)?$cap){0,2})"
        val state = "(${SpokenIdentifiers.alt(t.usStates + t.usStateCodes)})"
        val zip = "(\\d{5}(?:-\\d{4}|\\s+(?i:dash|hyphen)\\s+(?:\\d{4}|$digitWord(?:\\s+$digitWord){3}))?|$digitWord(?:\\s+$digitWord){4}(?:\\s+(?i:dash|hyphen)\\s+(?:\\d{4}|$digitWord(?:\\s+$digitWord){3}))?)"
        Regex("(?<![^\\s(\\[{\"“'‘])$house$hs(?:(?:${SpokenIdentifiers.alt(t.streetDirections)})$hs)?$street$separator$unit$city$separator$state$separator$zip(?![\\p{L}\\d-])(?!\\s+(?:\\d|(?i:dash|hyphen)\\b))")
    }
    private fun addressNumber(raw: String): String? {
        if (raw.all(Char::isDigit)) return raw
        val words = raw.lowercase().split(Regex("\\s+"))
        for (split in 1 until words.size) {
            val tail = words.drop(split)
            val lowBase = DeterministicCleanup.units[tail.first()]?.takeIf { it in 10..19 } ?: DeterministicCleanup.tens[tail.first()] ?: continue
            val low = if (tail.size == 1) lowBase else if (tail.size == 2) lowBase + (DeterministicCleanup.units[tail[1]]?.takeIf { it in 1..9 } ?: continue) else continue
            val high = DeterministicCleanup.wordsToLong(words.take(split).joinToString(" ")) ?: continue
            if (high in 1..99) return (high * 100 + low).toString()
        }
        val digits = DeterministicCleanup.spokenDigits(raw)
        if (digits != null && !digits.startsWith('0')) return digits
        return DeterministicCleanup.wordsToLong(raw)?.takeIf { it > 0 }?.toString()
    }
    private fun zip(raw: String): String? {
        if (raw.matches(Regex("\\d{5}(?:-\\d{4})?"))) return raw
        val parts = raw.split(Regex("\\s+"))
        val dash = parts.indexOfFirst { it.lowercase() in setOf("dash", "hyphen") }
        val head = parts.take(if (dash < 0) parts.size else dash)
        val five = if (head.size == 1 && head[0].matches(Regex("\\d{5}"))) head[0] else if (head.size == 5) DeterministicCleanup.spokenDigits(head.joinToString(" ")) else null
        if (five == null || five.length != 5) return null
        if (dash < 0) return five
        val tail = parts.drop(dash + 1)
        val four = if (tail.size == 1 && tail[0].matches(Regex("\\d{4}"))) tail[0] else if (tail.size == 4) DeterministicCleanup.spokenDigits(tail.joinToString(" ")) else null
        return four?.takeIf { it.length == 4 }?.let { "$five-$it" }
    }
    private fun unit(raw: String): String? {
        if (raw.startsWith('#')) return raw
        val parts = raw.split(Regex("\\s+"))
        if (parts.size == 2 && parts[1].matches(Regex("\\d{1,5}[A-Z]?"))) return raw
        val tail = parts.drop(1).toMutableList()
        val letter = if (tail.size > 1 && tail.last().matches(Regex("[A-Z]"))) tail.removeAt(tail.lastIndex) else ""
        return addressNumber(tail.joinToString(" "))?.let { "${parts.first()} $it$letter" }
    }
    private fun lineBreak(raw: String) = raw.filter { it == '\n' || it == '\r' }
    private fun separator(raw: String): String {
        val br = lineBreak(raw)
        return if (br.isEmpty()) ", " else (if (',' in raw) "," else "") + br
    }
    fun apply(text: String, protect: (String) -> String): String {
        if (!Regex("\\d{5}|(?i:zero|oh|o|one|two|three|four|five|six|seven|eight|nine)\\s+\\w+\\s+\\w+\\s+\\w+\\s+\\w+").containsMatchIn(text)) return text
        return pattern.replace(text) { m ->
            val houseWords = m.groupValues[1]; val street = m.groupValues[2]; val city = m.groupValues[6]; val state = m.groupValues[8]; val zipWords = m.groupValues[10]
            val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
            val lead = before.trimEnd { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            val last = lead.lastOrNull()
            val mayStart = last == null || last == '\n' || last == '\r' || last in ",:;([{\"“'‘—–-!?" || lead.substringAfterLast(' ').lowercase() in t.addressIntroducers
            val longerNumber = Regex("(?i)(?:\\b(?:${SpokenIdentifiers.alt(DeterministicCleanup.units.keys + DeterministicCleanup.tens.keys)}|hundred|thousand)(?:[^\\S\\n]+and)?|\\d)[^\\S\\n]*$").containsMatchIn(before)
            val dateOrRange = Regex("\\b[A-Z][a-z]+\\s+\\d{1,2},\\s+$|(?i)(?:\\b(?:one|two|three|four|five|six|seven|eight|nine|\\d+)\\s+(?:to|through)|\\d+\\s*[-–:])\\s+$").containsMatchIn(before)
            val badStreet = Regex("^(?:AM|PM|UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT|FT)\\s").containsMatchIn(street)
            val cityWords = city.split(Regex("\\s+"))
            val moreZip = !zipWords.first().isDigit() && Regex("(?i)^\\s+(?:zero|oh|o|one|two|three|four|five|six|seven|eight|nine)(?![\\p{L}])").containsMatchIn(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 25)))
            val house = addressNumber(houseWords); val postcode = zip(zipWords)
            val isYear = house?.toIntOrNull() in 1900..2099 && Regex("(?i)\\b(?:from|of|for|on)\\s+$").containsMatchIn(before)
            val renderedUnit = if (m.groups[4] == null) "" else unit(m.groupValues[4])
            if (!mayStart || longerNumber || dateOrRange || badStreet || cityWords.first().length == 1 || cityWords.any { it in t.streetTypes } || moreZip || house == null || postcode == null || isYear || renderedUnit == null) m.value
            else {
                val houseEnd = houseWords.length
                val streetStart = m.value.indexOf(street, houseEnd)
                val direction = m.value.substring(houseEnd, streetStart).trim()
                val output = house + " " + (if (direction.isEmpty()) "" else "$direction ") + street + separator(m.groupValues[3]) +
                    (if (renderedUnit.isEmpty()) "" else renderedUnit + separator(m.groupValues[5])) + city + separator(m.groupValues[7]) + state +
                    lineBreak(m.groupValues[9]).ifEmpty { " " } + postcode
                protect(output)
            }
        }
    }
}
