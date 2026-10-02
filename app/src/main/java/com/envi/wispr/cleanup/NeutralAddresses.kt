package com.envi.wispr.cleanup

/** Address wording is licensed by a paired at/dot table, never by the device region. */
internal object NeutralAddresses {
    private val pairs = CleanupReferenceTables.addressWordPairs
    private val atAlt = SpokenIdentifiers.alt(pairs.keys)
    private val dotAlt = SpokenIdentifiers.alt(pairs.values.flatten().toSet())
    private val foreignDash = SpokenIdentifiers.alt(CleanupReferenceTables.neutralOnlyDashWords + setOf("strich", "bindestrich", "tiret", "guion", "guión", "trattino", "traço", "hífen", "hifen"))
    private val emailTlds = setOf("com", "org", "io", "co", "dev", "me", "net", "edu", "gov") + CleanupReferenceTables.countryCodeTLDs
    private val lowRiskTlds = setOf("com", "org", "io", "co", "dev", "me", "net")
    private val query = "question\\s+mark|equals|ampersand|hash|pound|percent|tilde|underscore|colon|dot|dash|hyphen|slash|fragezeichen|вопросительный\\s+знак|ponto\\s+de\\s+interrogação|punto\\s+interrogativo|point\\s+d'interrogation|punto\\s+de\\s+interrogación|znak\\s+zapytania|vraagteken"
    private val cue = Regex("\\p{L}*(?:mail|adres|correo)\\p{L}*|(?:^|[^\\p{L}])(?:napisz|wyślij|bericht)(?:[^\\p{L}]|$)", RegexOption.IGNORE_CASE)
    private val newCue = Regex("\\p{L}*(?:indirizzo|endereço|mensagem|адрес|письм|почт)\\p{L}*|(?:^|[^\\p{L}])(?:scrivi|manda)(?:[^\\p{L}]|$)", RegexOption.IGNORE_CASE)
    private val neutralDot = "(?:$dotAlt)(?!\\s+(?:de\\s+interrogação|interrogativo)(?![\\p{L}\\p{M}\\p{N}]))"
    private val dots = Regex("\\s+($dotAlt)(?=\\s)", RegexOption.IGNORE_CASE)
    private val sep = "(?:\\.|\\s+$neutralDot\\s+|\\s+(?:$foreignDash)\\s+)"

    fun inUnfinishedLink(text: String, start: Int): Boolean {
        val before = text.substring(maxOf(0, start - 48), start)
        return Regex("(?:https?|www)(?:[:/\\s].*)?$|(?:barra|ukośnik|schrägstrich|слэш|слеш|косая\\s+черта|barre\\s+oblique|schuine\\s+streep)\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
    }

    private fun followsSyntax(text: String, end: Int): Boolean = Regex("^\\s+(?:$query)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(end, minOf(text.length, end + 64)))
    private fun startsInside(text: String, start: Int): Boolean = Regex("(?:(?:^|\\s)(?:$dotAlt|$foreignDash)\\s+|\\.)$", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(maxOf(0, start - 48), start))
    private fun render(raw: String): String = raw.replace(Regex("\\s+(?:$foreignDash)\\s+", RegexOption.IGNORE_CASE), "-").replace(Regex("\\s+$neutralDot\\s+", RegexOption.IGNORE_CASE), ".")

    fun normalize(input: String, neutral: Boolean): String {
        var text = emails(input, neutral)
        if (neutral) text = neutralPorts(gluedEmails(text))
        return urls(text, neutral)
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

    private fun emails(text: String, neutral: Boolean): String {
        val label = if (neutral) "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])" else "[a-z][a-z0-9_]*"
        val domLabel = if (neutral) label else "[a-z0-9][a-z0-9-]*"
        val endings = emailTlds + if (neutral) setOf("br", "it", "ру") else emptySet()
        val separator = if (neutral) sep else "(?:\\.|\\s+(?:$dotAlt)\\s+)"
        val re = Regex("(?<![\\p{L}\\p{M}\\p{N}_.@-])(?<name>$label(?:$separator$label){0,5})\\s+(?<at>$atAlt)(?<comma>,)?\\s+(?<dom>$domLabel(?:$separator$domLabel){0,5})$separator(?<tld>${SpokenIdentifiers.alt(endings)})(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return re.replace(text) { m ->
            val at = m.groups["at"]!!.value.lowercase()
            val name = m.groups["name"]!!.value
            val domain = m.groups["dom"]!!.value
            val tld = m.groups["tld"]!!.value
            val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
            val allDots = dots.findAll(" ${m.value} ").map { it.groupValues[1].lowercase() }.toList()
            val domainText = text.substring(m.groups["at"]!!.range.last + 1, m.range.last + 1)
            val domainDots = dots.findAll(" $domainText ").map { it.groupValues[1].lowercase() }.toList()
            val nameLabels = name.split(Regex(separator, RegexOption.IGNORE_CASE))
            val domLabels = domain.split(Regex(separator, RegexOption.IGNORE_CASE))
            val mailCue = Regex("\\be-?mail(?:\\s+me)?(?:\\s+at)?\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
            val addressCue = cue.containsMatchIn(before) || (at in setOf("chiocciola", "arroba", "собака", "sobaka") && newCue.containsMatchIn(before))
            val hasSpokenDash = Regex("\\s+(?:$foreignDash)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(m.value)
            val sourceASCII = m.value.all { it.code < 128 }
            val knownPair = allDots.all { it in pairs.getValue(at) }
            val alreadyDottedAllowed = neutral && at != "at" && (nameLabels.size > 1 || '-' in name || addressCue)
            val allowedTld = !(tld.equals("it", true) && domainDots.any { it == "dot" }) && (!tld.equals("ру", true) || domainDots.lastOrNull() == "точка")
            val foreignAtAllowed = !neutral || at != "at" || (domainDots.isNotEmpty() && allDots.all { it == "punkt" }) || (sourceASCII && nameLabels.size > 1 && domainDots.isNotEmpty())
            val fileName = nameLabels.size > 1 && nameLabels.last().lowercase() in CleanupReferenceTables.dottedNameRefusedSuffixes
            val proseDomain = at == "at" && allDots.all { it == "dot" } && domLabels.first().lowercase() in CleanupReferenceTables.englishProseDomainWords
            val website = at == "at" && nameLabels.size == 1 && domLabels.size > 1 && !mailCue
            val commaAllowed = m.groups["comma"] == null || (neutral && at in setOf("małpa", "malpa") && addressCue)
            if (Regex("^\\s+(?:$dotAlt)\\s+[\\p{L}\\p{N}]", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 49))) || startsInside(text, m.range.first) || !knownPair || !allowedTld || !foreignAtAllowed || fileName || proseDomain || website || !commaAllowed || (neutral && refusedName(nameLabels, at, allDots)) || (domainDots.isEmpty() && !alreadyDottedAllowed) || (neutral && followsSyntax(text, m.range.last + 1))) m.value
            else render(name) + "@" + render(domain) + "." + if (neutral && tld.equals("ру", true)) "ru" else tld
        }
    }

    private fun gluedEmails(input: String): String {
        val label = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*(?<![_-])"
        val domain = "(?<dom>$label(?:\\.$label){0,5})"
        val end = "\\.(?<tld>${SpokenIdentifiers.alt(emailTlds + setOf("br", "it"))})(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])"
        val begin = "(?<![\\p{L}\\p{M}\\p{N}_.@-])"
        val shapes = listOf(
            "$begin(?<name>[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_.-]{0,63}?)(?<at>apenstaartje|apestaartje)(?<gap>\\s?)$domain$end",
            "$begin(?<name>[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_.-]{0,63}?)(?<![cC])(?<at>chiocciola)\\s?$domain$end",
            "$begin(?<name>$label(?:\\.$label){0,5})\\s+(?<at>arroba)$domain$end",
            "$begin(?<name>$label(?:\\.$label){0,5})\\.(?<at>собака)\\.$domain$end",
        )
        var text = input
        shapes.forEachIndexed { index, pattern ->
            text = Regex(pattern, RegexOption.IGNORE_CASE).replace(text) { m ->
                val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
                val name = m.groups["name"]!!.value
                val at = m.groups["at"]!!.value.lowercase()
                val dom = m.groups["dom"]!!.value
                val refuseName = when (at) {
                    "chiocciola" -> name.lowercase() in CleanupReferenceTables.italianNameRefusedWords
                    "arroba" -> name.lowercase() in CleanupReferenceTables.portugueseNameRefusedWords && Regex("(?i)\\p{L}*(?:endereço|mensagem)\\p{L}*").containsMatchIn(before)
                    "собака" -> name.lowercase() in CleanupReferenceTables.russianNameRefusedWords
                    else -> false
                }
                val isPlural = dom.lowercase().startsWith("s") && (index == 2 || (index == 0 && m.groups["gap"]!!.value.isEmpty()))
                if ((!(cue.containsMatchIn(before) || (index != 0 && newCue.containsMatchIn(before)))) || name.lowercase() in CleanupReferenceTables.neutralNameRefusedWords || refuseName || isPlural || name.endsWith('.') || name.endsWith('-') || startsInside(text, m.range.first) || followsSyntax(text, m.range.last + 1)) m.value
                else "$name@$dom.${m.groups["tld"]!!.value}"
            }
        }
        return text
    }

    private fun neutralPorts(input: String): String {
        var text = input
        for (words in urlWords.drop(1)) {
            val slash = "\\s+(?:${words.slash})\\s+[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}_-]*"
            val re = Regex("(?<![\\w./:@-])localhost\\s+(?:${words.colon})\\s+(?<port>\\d{1,5})(?<path>(?:$slash){0,8})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val port = m.groups["port"]!!.value.toInt()
                if (port !in 1..65535 || followsSyntax(text, m.range.last + 1)) m.value else "localhost:$port" + m.groups["path"]!!.value.replace(Regex("\\s+(?:${words.slash})\\s+", RegexOption.IGNORE_CASE), "/")
            }
        }
        return text
    }

    private data class Words(val dots: String, val slash: String, val colon: String, val glueSlash: Boolean = false)
    private val urlWords = listOf(
        Words("dot", "(?:forward\\s+)?slash", "colon"),
        Words("point", "barre\\s+oblique|bar\\s+oblique", "deux\\s+points|deux-points"),
        Words("punto", "barra(?:\\s+obliqua)?", "dos\\s+puntos|due\\s+punti"),
        Words("kropka", "ukośnik", "dwukropek"),
        Words("punt", "schuine\\s+streep", "dubbele\\s+punt", true),
        Words("punkt", "schrägstrich", "doppelpunkt"),
        Words("точка", "слэш|слеш|косая\\s+черта", "двоеточие"),
        Words("ponto(?!\\s+de\\s+interrogação)", "barra", "dois\\s+pontos"),
    )

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
            val re = Regex("(?<![\\w.@/-])$prefix$host$path(?![\\p{L}\\p{M}\\p{N}_-]|\\.[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            text = re.replace(text) { m ->
                val hostRaw = if (neutral && !m.groups["host"]!!.value.substringBefore('.').substringBefore(' ').equals("www", true)) m.groups["host"]!!.value.replace(Regex("^$www", RegexOption.IGNORE_CASE), "www") else m.groups["host"]!!.value
                val labels = hostRaw.split(Regex(separator, RegexOption.IGNORE_CASE))
                val ending = m.groups["tld"]!!.value.lowercase()
                val before = text.substring(maxOf(0, m.range.first - 48), m.range.first)
                val spoken = Regex("\\s+(?:${words.dots})\\s+", RegexOption.IGNORE_CASE).containsMatchIn(m.value)
                val badStart = startsInside(text, m.range.first) || Regex("(?:slash|barra|ukośnik|schrägstrich)\\s+$", RegexOption.IGNORE_CASE).containsMatchIn(before)
                val mixedEnding = !neutral && spoken && text.getOrNull(m.groups["tld"]!!.range.first - 1) == '.'
                val badHost = mixedEnding || !neutral && spoken && ((labels.size == 1 && ending !in lowRiskTlds) || (labels.size == 1 && labels[0].length > 1 && labels[0].lowercase() in CleanupReferenceTables.englishProseDomainWords))
                if (badStart || badHost || followsSyntax(text, m.range.last + 1)) m.value
                else {
                    val hostWritten = labels.joinToString(".").let { if (labels.size > 1 && labels.first().equals("W", true)) "www." + labels.drop(1).joinToString(".") else it }
                    val protocol = m.groups["protocol"]?.value?.lowercase()?.plus("://") ?: ""
                    val pathWritten = m.groups["path"]!!.value.replace(Regex(slashSep, RegexOption.IGNORE_CASE), "/")
                    protocol + hostWritten + "." + (if (ending == "ру") "ru" else m.groups["tld"]!!.value) + pathWritten
                }
            }
        }
        return text
    }
}
