package com.envi.wispr.cleanup

/** Address wording is licensed by a paired at/dot table, never by the device region. */
internal object NeutralAddresses {
    private val pairs = CleanupReferenceTables.addressWordPairs
    private val atAlt = SpokenIdentifiers.alt(pairs.keys)
    private val dotAlt = SpokenIdentifiers.alt(pairs.values.flatten().toSet())
    private val foreignDash = SpokenIdentifiers.alt(CleanupReferenceTables.neutralOnlyDashWords + setOf("strich", "bindestrich", "tiret", "guion", "guión", "trattino", "traço", "hífen", "hifen", "dash", "hyphen"))
    private val emailTlds = setOf("com", "org", "io", "co", "dev", "me", "net", "edu", "gov") + CleanupReferenceTables.countryCodeTLDs
    private val lowRiskTlds = setOf("com", "org", "io", "co", "dev", "me", "net")
    private val cue = cleaningRegex("\\p{L}*(?:mail|adres|correo)\\p{L}*|(?:^|[^\\p{L}])(?:napisz|wyślij|bericht)(?:[^\\p{L}]|$)", RegexOption.IGNORE_CASE)
    private val newCue = cleaningRegex("\\p{L}*(?:indirizzo|endereço|mensagem|адрес|письм|почт)\\p{L}*|(?:^|[^\\p{L}])(?:scrivi|manda)(?:[^\\p{L}]|$)", RegexOption.IGNORE_CASE)
    private val neutralDot = NeutralLinks.guardedDotAlt(pairs.values.flatten().toSet())
    private val dots = cleaningRegex("\\s+($dotAlt)(?=\\s)", RegexOption.IGNORE_CASE)
    private val sep = "(?:\\.|\\s+$neutralDot\\s+|\\s+(?:$foreignDash)\\s+)"

    fun inUnfinishedLink(text: String, start: Int): Boolean = NeutralLinks.startsEarlier(text, start)

    private val englishContinuation = cleaningRegex("^\\s+(?:question\\s+mark|equals|ampersand|hash|pound|percent|tilde|underscore|colon|dot|dash|hyphen|slash)\\b", RegexOption.IGNORE_CASE)
    private fun followsSyntax(text: String, end: Int): Boolean = NeutralLinks.continues(text, end)
    private fun englishContinues(text: String, end: Int): Boolean = englishContinuation.containsMatchIn(text.substring(end, minOf(text.length, end + 64)))
    private fun startsAfterDot(text: String, start: Int): Boolean = cleaningRegex("(?:(?:^|\\s)(?:$dotAlt)\\s+|\\.)$", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(maxOf(0, start - 24), start))
    private fun startsInside(text: String, start: Int): Boolean = cleaningRegex("(?:(?:^|\\s)(?:$dotAlt|$foreignDash)\\s+|\\.)$", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(maxOf(0, start - 48), start))
    private fun render(raw: String): String = raw.replace(cleaningRegex("\\s+(?:$foreignDash)\\s+", RegexOption.IGNORE_CASE), "-").replace(cleaningRegex("\\s+$neutralDot\\s+", RegexOption.IGNORE_CASE), ".")

    fun normalize(input: String, neutral: Boolean): String {
        var text = emails(input, neutral)
        if (neutral) return NeutralLinks.normalize(gluedEmails(text))
        return urls(text, neutral = false)
    }

    private fun refusedName(labels: List<String>, at: String, foundDots: List<String>): Boolean {
        if (labels.size != 1) return false
        val name = labels.single().lowercase()
        if (name in CleanupReferenceTables.neutralNameRefusedWords) return true
        return when (at) {
            "собака", "sobaka" -> name in CleanupReferenceTables.russianNameRefusedWords
            "chiocciola" -> name in CleanupReferenceTables.italianNameRefusedWords
            "arroba" -> "ponto" in foundDots && name in CleanupReferenceTables.portugueseNameRefusedWords
            else -> false
        }
    }

    private fun furtherLabel(text: String, m: MatchResult): Boolean = cleaningRegex("^\\s+(?:$dotAlt)\\s+[\\p{L}\\p{N}]", RegexOption.IGNORE_CASE)
        .containsMatchIn(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 49)))
    private fun followedBySlash(text: String, m: MatchResult): Boolean = cleaningRegex("^\\s+(?:barre\\s+oblique|bar\\s+oblique|barra(?:\\s+obliqua)?|ukośnik|schuine\\s+streep|schrägstrich|слэш|слеш|косая\\s+черта)\\s*[\\p{L}\\p{N}]|^\\s*/\\s*[\\p{L}\\p{N}]", RegexOption.IGNORE_CASE)
        .containsMatchIn(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 41)))
    private fun tldAllowed(tld: String, before: String): Boolean = when (tld.lowercase()) {
        "it" -> !cleaningRegex("(?:^|\\s)dot\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
        "ру" -> cleaningRegex("(?:^|\\s)точка\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
        else -> true
    }
    private fun writtenTld(tld: String) = if (tld.equals("ру", true)) "ru" else tld
    private fun emails(input: String, neutral: Boolean): String {
        val dot = if (neutral) neutralDot else "(?:$dotAlt)"
        val separator = "(?:\\.|\\s+$dot\\s+)"
        val endings = emailTlds + if (neutral) setOf("br", "it", "ру") else emptySet()
        val re = cleaningRegex("(?<![\\w.@])(?<name>[a-z][a-z0-9_]*(?:$separator[a-z0-9_]+){0,5})\\s+(?<at>$atAlt)\\s+(?<dom>[a-z][a-z0-9-]*(?:$separator[a-z0-9][a-z0-9-]*){0,5})$separator(?<tld>${SpokenIdentifiers.alt(endings)})\\b(?!\\.[a-z0-9])", RegexOption.IGNORE_CASE)
        val text = re.replace(input) { m ->
            val at = m.groups["at"]!!.value.lowercase(); val name = m.groups["name"]!!.value; val domain = m.groups["dom"]!!.value; val tld = m.groups["tld"]!!.value
            val allDots = dots.findAll(" ${m.value} ").map { it.groupValues[1].lowercase() }.toList()
            val domainHalf = input.substring(m.groups["at"]!!.range.last + 1, m.range.last + 1)
            val domainDots = dots.findAll(" $domainHalf ").toList()
            val nameLabels = name.split(cleaningRegex(separator, RegexOption.IGNORE_CASE)); val domainLabels = domain.split(cleaningRegex(separator, RegexOption.IGNORE_CASE))
            val before = input.substring(maxOf(0, m.range.first - 24), m.range.first)
            val emailCue = cleaningRegex("\\be-?mail(?:\\s+me)?(?:\\s+at)?\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
            val beforeTld = input.substring(m.range.first, m.groups["tld"]!!.range.first)
            val file = nameLabels.size > 1 && nameLabels.last().lowercase() in CleanupReferenceTables.dottedNameRefusedSuffixes
            val prose = at == "at" && allDots.all { it == "dot" } && domainLabels.first().lowercase() in CleanupReferenceTables.englishProseDomainWords
            if (startsAfterDot(input, m.range.first) || (neutral && startsInside(input, m.range.first)) || domainDots.isEmpty() || !allDots.all { it in pairs.getValue(at) } || (neutral && refusedName(nameLabels, at, allDots)) || (neutral && !tldAllowed(tld, beforeTld)) || (at == "at" && nameLabels.size == 1 && domainLabels.size > 1 && !emailCue) || (neutral && at == "at" && nameLabels.size == 1 && "dot" in allDots) || furtherLabel(input, m) || (neutral && followedBySlash(input, m)) || file || prose) m.value
            else nameLabels.joinToString(".") + "@" + (domainLabels + writtenTld(tld)).joinToString(".")
        }
        return if (neutral) unicodeEmails(text) else text
    }

    private fun unicodeEmails(text: String): String {
        val label = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])"
        val endings = SpokenIdentifiers.alt(emailTlds + setOf("br", "it", "ру"))
        val re = cleaningRegex("(?<![\\p{L}\\p{M}\\p{N}_.@-])(?<name>$label(?:$sep$label){0,5})\\s+(?<at>$atAlt)(?<comma>,)?\\s+(?<dom>$label(?:$sep$label){0,5})$sep(?<tld>$endings)(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return re.replace(text) { m ->
            val at = m.groups["at"]!!.value.lowercase(); val name = m.groups["name"]!!.value; val domain = m.groups["dom"]!!.value; val tld = m.groups["tld"]!!.value
            val allDots = dots.findAll(" ${m.value} ").map { it.groupValues[1].lowercase() }.toList()
            val domainHalf = text.substring(m.groups["at"]!!.range.last + 1, m.range.last + 1)
            val domainSpoken = dots.containsMatchIn(" $domainHalf ")
            val nameLabels = name.split(cleaningRegex(sep, RegexOption.IGNORE_CASE))
            val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
            val ownCue = at in setOf("chiocciola", "arroba", "собака", "sobaka") && newCue.containsMatchIn(before)
            val spokenDash = cleaningRegex("\\s+(?:$foreignDash)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(m.value)
            val ascii = (m.value.dropLast(tld.length) + writtenTld(tld)).all { it.code < 128 }
            val compound = nameLabels.size > 1 || '-' in name
            val portugueseMissing = at == "arroba" && nameLabels.size == 1 && nameLabels.single().lowercase() in CleanupReferenceTables.portugueseNameRefusedWords && cleaningRegex("(?i)\\p{L}*(?:endereço|mensagem)\\p{L}*").containsMatchIn(before)
            val file = nameLabels.size > 1 && nameLabels.last().lowercase() in CleanupReferenceTables.dottedNameRefusedSuffixes
            val beforeTld = text.substring(m.range.first, m.groups["tld"]!!.range.first)
            val innerTld = domain.split(cleaningRegex(sep, RegexOption.IGNORE_CASE)).any { it.equals("ру", true) }
            if (startsInside(text, m.range.first) || !allDots.all { it in pairs.getValue(at) } || (at == "at" && (allDots.isEmpty() || allDots.any { it != "punkt" } || !domainSpoken)) || (ascii && domainSpoken && !spokenDash) || (!domainSpoken && !(compound || cue.containsMatchIn(before) || ownCue)) || (m.groups["comma"] != null && !(at in setOf("małpa", "malpa") && cue.containsMatchIn(before))) || furtherLabel(text, m) || followedBySlash(text, m) || refusedName(nameLabels, at, allDots) || portugueseMissing || !tldAllowed(tld, beforeTld) || innerTld || file) m.value
            else render(name) + "@" + render(domain) + "." + writtenTld(tld)
        }
    }

    private fun gluedEmails(input: String): String {
        val label = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])"
        val domain = "(?<dom>$label(?:\\.$label){0,5})"
        val dutchDomain = "(?<dom>$label(?:\\.$label)*)"
        val end = "\\.(?<tld>${SpokenIdentifiers.alt(emailTlds + setOf("br", "it"))})(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])"
        val begin = "(?<![\\p{L}\\p{M}\\p{N}_.@-])"
        val shapes = listOf(
            "$begin(?<name>[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_.-]{0,63}?)(?<at>apenstaartje|apestaartje)(?<gap>\\s?)$dutchDomain\\.(?<tld>${SpokenIdentifiers.alt(emailTlds)})(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])",
            "$begin(?<name>[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_.-]{0,63}?)(?<![cC])(?<at>chiocciola)\\s?$domain$end",
            "$begin(?<name>$label(?:\\.$label){0,5})\\s+(?<at>arroba)$domain$end",
            "$begin(?<name>$label(?:\\.$label){0,5})\\.(?<at>собака)\\.$domain$end",
        )
        var text = input
        shapes.forEachIndexed { index, pattern ->
            text = cleaningRegex(pattern, RegexOption.IGNORE_CASE).replace(text) { m ->
                val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
                val name = m.groups["name"]!!.value
                val at = m.groups["at"]!!.value.lowercase()
                val dom = m.groups["dom"]!!.value
                val refuseName = when (at) {
                    "chiocciola" -> name.lowercase() in CleanupReferenceTables.italianNameRefusedWords
                    "arroba" -> name.lowercase() in CleanupReferenceTables.portugueseNameRefusedWords && cleaningRegex("(?i)\\p{L}*(?:endereço|mensagem)\\p{L}*").containsMatchIn(before)
                    "собака" -> name.lowercase() in CleanupReferenceTables.russianNameRefusedWords
                    else -> false
                }
                val isPlural = dom.lowercase().startsWith("s") && (index == 2 || (index == 0 && m.groups["gap"]!!.value.isEmpty()))
                if ((!(cue.containsMatchIn(before) || (index != 0 && newCue.containsMatchIn(before)))) || name.lowercase() in CleanupReferenceTables.neutralNameRefusedWords || refuseName || isPlural || name.endsWith('.') || name.endsWith('-') || startsInside(text, m.range.first) || furtherLabel(text, m) || followedBySlash(text, m) || (index != 0 && (!tldAllowed(m.groups["tld"]!!.value, ".") || dom.split('.').any { it.equals("ру", true) }))) m.value
                else "$name@$dom.${m.groups["tld"]!!.value}"
            }
        }
        return text
    }

    private data class Words(val dots: String, val slash: String, val colon: String, val glueSlash: Boolean = false)
    private val urlWords = listOf(Words("dot", "(?:forward\\s+)?slash", "colon"))

    private fun urls(input: String, neutral: Boolean): String {
        var text = input
        val label = if (neutral) "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])" else "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
        for (words in if (neutral) urlWords else urlWords.take(1)) {
            val separator = "(?:\\.|\\s+(?:${words.dots})\\s+)"
            val ends = lowRiskTlds + CleanupReferenceTables.countryCodeTLDs + if (neutral) setOf("ai", "app", "xyz", "br", "it", "ру") else emptySet()
            val prefix = "(?:(?<protocol>https?)\\s+(?:${words.colon}|:)\\s+(?:${words.slash})\\s+(?:${words.slash})\\s+)?"
            val www = if (neutral) "(?:www|w\\s+w\\s+w|wu\\s+wu\\s+wu|uve\\s+doble\\s+uve\\s+doble\\s+uve\\s+doble|triple\\s+w|we\\s+we\\s+we|вэ\\s+вэ\\s+вэ|dáblio\\s+dáblio\\s+dáblio|vu\\s+vu\\s+vu)" else "www"
            val host = "(?<host>(?:$www|$label)(?:$separator$label){0,5})$separator(?<tld>${SpokenIdentifiers.alt(ends)})"
            val slashSep = if (words.glueSlash) "\\s+(?:${words.slash})\\s*" else "\\s+(?:${words.slash})\\s+"
            val path = "(?<path>(?:$slashSep$label){0,8})"
            val re = cleaningRegex("(?<![\\w.@/-])$prefix$host$path(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val hostRaw = if (neutral && !m.groups["host"]!!.value.substringBefore('.').substringBefore(' ').equals("www", true)) m.groups["host"]!!.value.replace(cleaningRegex("^$www", RegexOption.IGNORE_CASE), "www") else m.groups["host"]!!.value
                val labels = hostRaw.split(cleaningRegex(separator, RegexOption.IGNORE_CASE))
                val ending = m.groups["tld"]!!.value.lowercase()
                val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
                val spoken = cleaningRegex("\\s+(?:${words.dots})\\s+", RegexOption.IGNORE_CASE).containsMatchIn(m.value)
                val badStart = startsInside(text, m.range.first) || cleaningRegex("(?:slash|barra|ukośnik|schrägstrich)\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
                val mixedEnding = !neutral && spoken && text.getOrNull(m.groups["tld"]!!.range.first - 1) == '.'
                val badHost = mixedEnding || !neutral && spoken && ((labels.size == 1 && ending !in lowRiskTlds) || (labels.size == 1 && labels[0].length > 1 && labels[0].lowercase() in CleanupReferenceTables.englishProseDomainWords))
                if (badStart || badHost || englishContinues(text, m.range.last + 1)) m.value
                else {
                    val hostWritten = labels.joinToString(".").let { if (labels.size > 1 && labels.first().equals("W", true)) "www." + labels.drop(1).joinToString(".") else it }
                    val protocol = m.groups["protocol"]?.value?.lowercase()?.plus("://") ?: ""
                    val pathWritten = m.groups["path"]!!.value.replace(cleaningRegex(slashSep, RegexOption.IGNORE_CASE), "/")
                    protocol + hostWritten + "." + (if (ending == "ру") "ru" else m.groups["tld"]!!.value) + pathWritten
                }
            }
        }
        return text
    }
}
