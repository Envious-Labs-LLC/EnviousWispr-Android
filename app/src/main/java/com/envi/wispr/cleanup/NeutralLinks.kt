package com.envi.wispr.cleanup

/** The reference's four neutral link producers; plain foreign prose is never a fifth producer. */
internal object NeutralLinks {
    private data class Words(val dot: List<String>, val slash: List<String>, val colon: List<String>, val glue: Boolean = false, val query: List<String> = emptyList())
    private val rows = listOf(
        Words(listOf("point"), listOf("barre oblique", "bar oblique"), listOf("deux points", "deux-points")),
        Words(listOf("punto"), listOf("barra"), listOf("dos puntos")),
        Words(listOf("kropka"), listOf("ukośnik"), listOf("dwukropek")),
        Words(listOf("punt"), listOf("schuine streep"), listOf("dubbele punt"), true),
        Words(listOf("punkt"), listOf("schrägstrich"), listOf("doppelpunkt"), query = listOf("fragezeichen")),
        Words(listOf("точка"), listOf("слэш", "слеш", "косая черта"), listOf("двоеточие"), query = listOf("вопросительный знак")),
        Words(listOf("ponto"), listOf("barra"), listOf("dois pontos"), query = listOf("ponto de interrogação")),
        Words(listOf("punto"), listOf("barra obliqua", "barra"), listOf("due punti"), query = listOf("punto interrogativo")),
    )
    private fun phrases(values: Collection<String>) = SpokenIdentifiers.alt(values).replace("'", "['’]")
    private val queryWords = listOf("point d'interrogation", "signo de interrogación", "signo de interrogacion", "znak zapytania", "vraagteken", "question mark") + rows.flatMap { it.query }
    private val label = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])"
    private val www = "(?:www|w\\s+w\\s+w|wu\\s+wu\\s+wu|uve\\s+doble\\s+uve\\s+doble\\s+uve\\s+doble|triple\\s+w|we\\s+we\\s+we|вэ\\s+вэ\\s+вэ|dáblio\\s+dáblio\\s+dáblio|vu\\s+vu\\s+vu)"
    private val endings = setOf("com", "org", "io", "co", "dev", "me", "net", "edu", "gov", "ai", "app", "xyz", "br", "it", "ру") + CleanupReferenceTables.countryCodeTLDs
    private val dashWords = CleanupReferenceTables.neutralOnlyDashWords + setOf("strich", "bindestrich", "tiret", "guion", "guión", "trattino", "traço", "hífen", "hifen", "dash", "hyphen")
    private val syntax = phrases(rows.flatMap { it.dot + it.slash + it.colon } + queryWords + dashWords)
    private val continuation = Regex("^(?:\\s+(?:$syntax)(?:\\s+|$)|\\s*/|[.:][\\p{L}\\p{N}]|[\\p{L}\\p{M}\\p{N}_@-])", RegexOption.IGNORE_CASE)
    private val earlier = Regex("(?:^|[^\\p{L}])(?:https?|${phrases(rows.flatMap { it.dot + it.slash + it.colon })}|${phrases(CleanupReferenceTables.addressWordPairs.keys)}),?\\s+$|/\\s*$", RegexOption.IGNORE_CASE)
    fun continues(text: String, end: Int): Boolean = continuation.containsMatchIn(text.substring(end, minOf(text.length, end + 96)))
    fun startsEarlier(text: String, start: Int): Boolean = earlier.containsMatchIn(text.substring(maxOf(0, start - 48), start))
    private fun dot(row: Words): String {
        val tails = queryWords.mapNotNull { query -> row.dot.firstOrNull { query.startsWith("$it ", true) }?.let { query.substring(it.length + 1) } }
        return "(?:${phrases(row.dot)})" + if (tails.isEmpty()) "" else "(?!\\s+(?:${phrases(tails)})(?![\\p{L}\\p{M}\\p{N}]))"
    }
    private fun slash(row: Words): String = "(?:" + row.slash.sortedByDescending(String::length).joinToString("|") { one ->
        val tails = rows.flatMap { it.slash }.filter { it.startsWith("$one ", true) }.map { it.substring(one.length + 1) }
        phrases(listOf(one)) + if (tails.isEmpty()) "" else "(?!\\s+(?:${phrases(tails)})(?![\\p{L}\\p{M}\\p{N}]))"
    } + ")"
    private fun separator(row: Words) = "(?:\\.|\\s+${dot(row)}\\s+)"
    private fun host(row: Words, portRequired: Boolean): String {
        val sep = separator(row)
        val domain = "(?:$www$sep)?$label(?:$sep$label){0,5}$sep(?:${phrases(endings)})"
        val ip = "\\d{1,3}(?:(?:\\.|\\s+(?:${phrases(row.dot)})\\s+)\\d{1,3}){3}"
        val port = "\\s+(?:${phrases(row.colon)})\\s+\\d{1,5}"
        return "(?:$domain|$ip|localhost(?:$port)${if (portRequired) "" else "?"})"
    }
    private fun pathSplit(row: Words) = "\\s+${slash(row)}" + if (row.glue) "\\s*" else "\\s+"
    private fun path(row: Words) = "(?:${pathSplit(row)}$label)"
    private fun canonicalHost(raw: String, row: Words): String? {
        if (Regex("^localhost(?:\\s+(?:${phrases(row.colon)})\\s+\\d{1,5})?$", RegexOption.IGNORE_CASE).matches(raw)) {
            val port = Regex("\\d+$").find(raw)?.value ?: return raw
            if (port.toInt() !in 1..65535) return null
            return raw.take(9) + ":" + port
        }
        val alias = Regex("^$www", RegexOption.IGNORE_CASE).find(raw)
        val text = if (alias != null && !alias.value.equals("www", true)) "www" + raw.substring(alias.range.last + 1) else raw
        val labels = text.split(Regex(separator(row), RegexOption.IGNORE_CASE)).toMutableList()
        if (labels.dropLast(1).any { it.equals("ру", true) }) return null
        val ending = labels.last()
        if (ending.equals("it", true) && Regex("(?:^|\\s)dot\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(text.dropLast(ending.length))) return null
        if (ending.equals("ру", true)) {
            if (!Regex("(?:^|\\s)точка\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(text.dropLast(ending.length))) return null
            labels[labels.lastIndex] = "ru"
        }
        return labels.joinToString(".")
    }
    private fun pathWords(raw: String, row: Words) = raw.split(Regex(pathSplit(row), RegexOption.IGNORE_CASE)).map(String::trim).filter(String::isNotEmpty)
    private fun gluedWord(raw: String, row: Words) = row.glue && Regex("schuine\\s+streep(?:je|jes|en|e|s)(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    private fun spokenHostAllowed(raw: String, row: Words): Boolean {
        if (!Regex("\\s+(?:${phrases(row.dot)})\\s+", RegexOption.IGNORE_CASE).containsMatchIn(raw) || Regex("^$www", RegexOption.IGNORE_CASE).containsMatchIn(raw) || raw.startsWith("localhost", true)) return true
        return raw.split(Regex(separator(row), RegexOption.IGNORE_CASE)).last().lowercase() !in setOf("ai", "app", "xyz")
    }
    fun normalize(input: String): String {
        var text = input
        // Schemes precede paths/WWW/ports, so a refused prefix cannot license a tail conversion.
        for (row in rows) {
            val re = Regex("(?<![\\p{L}\\p{N}])(?<protocol>https?)(?:\\s+(?:${phrases(row.colon)})\\s+${slash(row)}\\s+${slash(row)}\\s+|://\\s*)(?<host>${host(row, false)})(?<path>${path(row)}*)(?![\\p{L}\\p{M}\\p{N}_@-])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val rawHost = m.groups["host"]!!.value; val rawPath = m.groups["path"]!!.value
                val written = canonicalHost(rawHost, row)
                val spokenScheme = Regex("^https?\\s", RegexOption.IGNORE_CASE).containsMatchIn(m.value)
                val spokenRest = Regex("\\s+(?:${phrases(row.dot + row.colon)})\\s+", RegexOption.IGNORE_CASE).containsMatchIn(rawHost) || rawPath.isNotEmpty() || Regex("^$www\\s", RegexOption.IGNORE_CASE).containsMatchIn(rawHost)
                if (continues(text, m.range.last + 1) || written == null || gluedWord(rawPath, row) || !(spokenScheme || spokenRest)) m.value
                else m.groups["protocol"]!!.value + "://" + written + pathWords(rawPath, row).joinToString("") { "/$it" }
            }
        }
        for (row in rows) {
            val re = Regex("(?<![\\p{L}\\p{M}\\p{N}_.@/:-])(?<host>${host(row, true)})(?<path>${path(row)}+)(?![\\p{L}\\p{M}\\p{N}_@-])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val rawHost = m.groups["host"]!!.value; val rawPath = m.groups["path"]!!.value
                val written = canonicalHost(rawHost, row)
                if (continues(text, m.range.last + 1) || startsEarlier(text, m.range.first) || !spokenHostAllowed(rawHost, row) || written == null || gluedWord(rawPath, row)) m.value
                else written + pathWords(rawPath, row).joinToString("") { "/$it" }
            }
        }
        for (row in rows) {
            val sep = separator(row)
            val re = Regex("(?<![\\p{L}\\p{M}\\p{N}_.@/-])(?<host>$www$sep$label(?:$sep$label){0,5}$sep(?:${phrases(endings)}))", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val rawHost = m.groups["host"]!!.value
                val spoken = Regex("\\s+${dot(row)}\\s+", RegexOption.IGNORE_CASE).containsMatchIn(rawHost) || Regex("^(?!www)$www", RegexOption.IGNORE_CASE).containsMatchIn(rawHost)
                if (!spoken || continues(text, m.range.last + 1) || startsEarlier(text, m.range.first)) m.value else canonicalHost(rawHost, row) ?: m.value
            }
        }
        val port = Regex("(?<![\\p{L}\\p{N}])(?<host>localhost)\\s+(?:${phrases(rows.flatMap { it.colon })})\\s+(?<port>\\d+)(?![\\p{L}\\p{N}.,]\\d|[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return port.replace(text) { m ->
            val value = m.groups["port"]!!.value.toIntOrNull()
            if (continues(text, m.range.last + 1) || startsEarlier(text, m.range.first) || value == null || value !in 1..65535) m.value else "${m.groups["host"]!!.value}:${m.groups["port"]!!.value}"
        }
    }
}
