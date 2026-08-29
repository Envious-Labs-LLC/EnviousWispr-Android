package com.envi.wispr.vocabulary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Local vocabulary contract. Keep this model independent of Room/UI for safe wiring. */
data class CustomTerm(
    val spelling: String,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val priority: Int = 0,
    val forceReplace: Boolean = false,
    val caseSensitive: Boolean = false,
    val minSimilarityOverride: Double? = null,
    val usageCount: Long = 0,
    val imported: Boolean = false,
) {
    fun normalized(): CustomTerm = copy(
        spelling = spelling.trim(),
        aliases = aliases.map(String::trim).filter(String::isNotEmpty).distinct(),
        priority = priority.coerceIn(-100, 100),
    )
}

enum class MatchStrictness(val thresholdOverride: Double?) {
    LOOSE(0.72),
    DEFAULT(null),
    STRICT(0.92);

    companion object {
        fun from(value: Double?): MatchStrictness = when {
            value == null -> DEFAULT
            value <= 0.80 -> LOOSE
            value >= 0.88 -> STRICT
            else -> DEFAULT
        }
    }
}

object CustomTermAuthoring {
    fun includePendingAlias(aliases: List<String>, pendingAlias: String): List<String> {
        val candidate = pendingAlias.trim()
        if (candidate.isEmpty() || aliases.any { it.equals(candidate, ignoreCase = true) }) {
            return aliases
        }
        return aliases + candidate
    }
}

data class ImportPreview(
    val accepted: List<CustomTerm>,
    val collisions: List<CustomTerm>,
    val rejected: Int,
    /** A full EnviousWispr backup owns matching fields; plain text imports never do. */
    val replaceCollisions: Boolean = false,
)

object VocabularyTransfer {
    private const val HEADER = "enviouswispr-vocabulary-v2"
    private const val LEGACY_HEADER = "enviouswispr-vocabulary-v1"
    private const val MAC_FORMAT = "com.enviouswispr.custom-words"
    private const val MAC_VERSION = 1
    private const val LIMIT = 2_000
    private const val MAX_INPUT_CHARS = 2_000_000
    private const val MAX_ALIASES_PER_TERM = 256
    private const val MAX_STRING_LENGTH = 200

    fun export(terms: List<CustomTerm>): String {
        val exportable = terms.map { it.normalized() }.filter { it.spelling.isNotEmpty() }
        require(exportable.size <= LIMIT) { "Custom vocabulary cannot exceed $LIMIT terms." }
        return buildString {
            appendLine(HEADER)
            exportable.forEach { term ->
                val fields = listOf(
                    encode(term.spelling),
                    term.aliases.joinToString(",") { encode(it, delimiter = ',') },
                    encode(term.category.orEmpty()),
                    term.priority.toString(),
                    term.forceReplace.toString(),
                    term.caseSensitive.toString(),
                    term.minSimilarityOverride?.toString().orEmpty(),
                    term.imported.toString(),
                )
                appendLine(fields.joinToString("\t"))
            }
        }
    }

    fun preview(input: String, existing: List<CustomTerm> = emptyList()): ImportPreview {
        require(input.length <= MAX_INPUT_CHARS) { "That vocabulary file is too large." }
        if (input.trimStart().startsWith('{')) return previewMacBackup(input, existing)
        val lines = input.lineSequence().toList()
        val firstLine = lines.firstOrNull()
        require(
            firstLine == null ||
                !firstLine.startsWith("enviouswispr-vocabulary-") ||
                firstLine == HEADER || firstLine == LEGACY_HEADER
        ) { "That vocabulary file needs a newer version of EnviousWispr." }
        val data = (if (firstLine == HEADER || firstLine == LEGACY_HEADER) lines.drop(1) else lines)
            .filter { it.isNotBlank() }
        val current = existing.map { it.spelling.lowercase() }.toSet()
        val seen = mutableSetOf<String>()
        val accepted = mutableListOf<CustomTerm>()
        val collisions = mutableListOf<CustomTerm>()
        var rejected = 0
        data.take(LIMIT).forEach { line ->
            // Split top-level fields without decoding first. The alias field has a
            // second delimiter, so decoding it in this pass would decode an escaped
            // literal ``\\n`` twice and turn it into a newline.
            val rawFields = splitRaw(line, '\t')
            val fields = rawFields.mapIndexed { index, value ->
                if (index == 1) value else decode(value)
            }
            val parsedAliases = if (fields.size >= 7) {
                splitRaw(fields[1], ',').map { decode(it, delimiter = ',') }.filter(String::isNotBlank)
            } else {
                emptyList()
            }
            if (parsedAliases.size > MAX_ALIASES_PER_TERM) {
                rejected++
                return@forEach
            }
            val term = when {
                fields.size == 7 || fields.size == 8 -> {
                    val priority = fields[3].toIntOrNull()
                    val forceReplace = fields[4].toBooleanStrictOrNull()
                    val caseSensitive = fields[5].toBooleanStrictOrNull()
                    val overrideIndex = if (fields.size == 8) 6 else -1
                    val similarityOverride = if (overrideIndex >= 0 && fields[overrideIndex].isNotBlank()) {
                        fields[overrideIndex].toDoubleOrNull()
                    } else {
                        null
                    }
                    val imported = fields[if (fields.size == 8) 7 else 6].toBooleanStrictOrNull()
                    if (priority == null || forceReplace == null ||
                        caseSensitive == null || imported == null ||
                        (overrideIndex >= 0 && fields[overrideIndex].isNotBlank() && similarityOverride == null)
                    ) {
                        null
                    } else {
                        CustomTerm(
                            spelling = fields[0],
                            aliases = parsedAliases,
                            category = fields[2].ifBlank { null },
                            priority = priority,
                            forceReplace = forceReplace,
                            caseSensitive = caseSensitive,
                            minSimilarityOverride = similarityOverride,
                            imported = imported,
                        )
                    }
                }
                fields.size == 1 -> CustomTerm(fields[0])
                else -> null
            }?.normalized()
            if (term == null ||
                term.spelling.isBlank() ||
                term.spelling.length > MAX_STRING_LENGTH ||
                term.aliases.size > MAX_ALIASES_PER_TERM ||
                term.aliases.any { it.length > MAX_STRING_LENGTH } ||
                term.category?.length?.let { it > MAX_STRING_LENGTH } == true ||
                term.minSimilarityOverride?.let { !it.isFinite() || it !in 0.0..1.0 } == true
            ) {
                rejected++
                return@forEach
            }
            val key = term.spelling.lowercase()
            if (key in current || !seen.add(key)) collisions += term else accepted += term
        }
        rejected += (data.size - minOf(data.size, LIMIT))
        return ImportPreview(accepted, collisions, rejected)
    }

    private fun previewMacBackup(input: String, existing: List<CustomTerm>): ImportPreview {
        val root = runCatching { Json.parseToJsonElement(input).jsonObject }
            .getOrElse { throw IllegalArgumentException("That custom-words backup is damaged.") }
        val format = root["format"]?.let { element ->
            runCatching { element.jsonPrimitive }
                .getOrNull()
                ?.takeIf { it.isString }
                ?.content
        }
        val builtinsVersion = root["builtinsVersion"]?.let { element ->
            runCatching { element.jsonPrimitive }
                .getOrNull()
                ?.takeIf { !it.isString }
                ?.intOrNull
        }
        val isTransferBackup = root.containsKey("format") && format == MAC_FORMAT
        val isLiveMacLibrary = !root.containsKey("format") &&
            builtinsVersion != null &&
            runCatching { root.getValue("deletedBuiltinIds").jsonArray }.isSuccess
        require(isTransferBackup || isLiveMacLibrary) {
            "That JSON file is not an EnviousWispr custom-words file."
        }
        if (isLiveMacLibrary) {
            val deletedBuiltinIds = root.getValue("deletedBuiltinIds").jsonArray.mapIndexed { index, value ->
                value.jsonPrimitive.also {
                    require(it.isString) { "Deleted built-in ID $index is not text." }
                }.content
            }
            require(deletedBuiltinIds.isEmpty()) {
                "This Mac library disables built-in terms that Android cannot preserve yet."
            }
        }
        val version = root["version"]?.let { element ->
            runCatching { element.jsonPrimitive }
                .getOrNull()
                ?.takeIf { !it.isString }
                ?.intOrNull
        }
            ?: throw IllegalArgumentException("That custom-words backup has no valid version.")
        require(version in 1..MAC_VERSION) {
            "That custom-words backup needs a newer version of EnviousWispr."
        }
        val words = runCatching { root.getValue("words").jsonArray }
            .getOrElse { throw IllegalArgumentException("That custom-words backup has no valid words list.") }
        val current = existing.associateBy { it.spelling.lowercase() }
        val seen = mutableSetOf<String>()
        val accepted = mutableListOf<CustomTerm>()
        val collisions = mutableListOf<CustomTerm>()
        var rejected = (words.size - minOf(words.size, LIMIT)).coerceAtLeast(0)
        words.take(LIMIT).forEach { value ->
            val term = runCatching {
                val row = value.jsonObject
                fun requiredString(name: String): String = row.getValue(name).jsonPrimitive.let {
                    require(it.isString)
                    it.content
                }
                fun optionalString(name: String): String? = row[name]?.jsonPrimitive?.let {
                    require(it.isString)
                    it.content
                }
                fun optionalInt(name: String): Int? = row[name]?.let {
                    val primitive = it.jsonPrimitive
                    require(!primitive.isString)
                    primitive.intOrNull ?: error("$name must be an integer")
                }
                fun optionalLong(name: String): Long? = row[name]?.let {
                    val primitive = it.jsonPrimitive
                    require(!primitive.isString)
                    primitive.longOrNull ?: error("$name must be an integer")
                }
                fun optionalBoolean(name: String): Boolean? = row[name]?.let {
                    val primitive = it.jsonPrimitive
                    require(!primitive.isString)
                    primitive.booleanOrNull ?: error("$name must be a boolean")
                }
                fun optionalDouble(name: String): Double? = row[name]?.let {
                    if (it is JsonNull) return@let null
                    val primitive = it.jsonPrimitive
                    require(!primitive.isString)
                    primitive.doubleOrNull ?: error("$name must be a number")
                }

                val aliasValues = row.getValue("aliases").jsonArray
                require(aliasValues.size <= MAX_ALIASES_PER_TERM)
                val aliases = aliasValues.map { alias ->
                    alias.jsonPrimitive.also { require(it.isString) }.content
                }
                CustomTerm(
                    spelling = requiredString("canonical"),
                    aliases = aliases,
                    category = optionalString("category"),
                    priority = optionalInt("priority") ?: 0,
                    forceReplace = optionalBoolean("forceReplace") ?: false,
                    caseSensitive = optionalBoolean("caseSensitive") ?: false,
                    minSimilarityOverride = optionalDouble("minSimilarityOverride"),
                    usageCount = (optionalLong("frequencyUsed") ?: 0L).also { require(it >= 0L) },
                    imported = true,
                ).normalized()
            }.getOrNull()
            val valid = term != null && term.spelling.isNotBlank() &&
                term.spelling.length <= MAX_STRING_LENGTH &&
                term.aliases.size <= MAX_ALIASES_PER_TERM &&
                term.aliases.all { it.length <= MAX_STRING_LENGTH } &&
                term.category?.length?.let { it <= MAX_STRING_LENGTH } != false &&
                term.minSimilarityOverride?.let { it.isFinite() && it in 0.0..1.0 } != false
            if (!valid) {
                rejected++
                return@forEach
            }
            val key = term.spelling.lowercase()
            if (!seen.add(key)) {
                rejected++
            } else if (key in current) {
                collisions += term
            } else {
                accepted += term
            }
        }
        return ImportPreview(accepted, collisions, rejected, replaceCollisions = true)
    }

    /** Atomic at the caller boundary: returns a new list and never mutates the input list. */
    fun commit(existing: List<CustomTerm>, preview: ImportPreview): List<CustomTerm> =
        (existing + preview.accepted).distinctBy { it.spelling.lowercase() }

    /**
     * Encode a value without losing literal backslash sequences. Commas are escaped only
     * inside the alias field, while tabs delimit the seven top-level fields.
     */
    private fun encode(value: String, delimiter: Char? = null): String = buildString {
        value.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == '\t' -> append("\\t")
                character == '\n' -> append("\\n")
                delimiter != null && character == delimiter -> append('\\').append(character)
                else -> append(character)
            }
        }
    }

    /** Stateful splitter that preserves escape sequences for one subsequent decode pass. */
    private fun splitRaw(value: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { character ->
            if (escaped) {
                current.append('\\').append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == delimiter) {
                fields += current.toString()
                current.setLength(0)
            } else {
                current.append(character)
            }
        }
        if (escaped) current.append('\\')
        fields += current.toString()
        return fields
    }

    /** Decode one field. Unknown escapes retain both characters verbatim. */
    private fun decode(value: String, delimiter: Char? = null): String = buildString {
        var escaped = false
        value.forEach { character ->
            if (escaped) {
                when {
                    character == 'n' -> append('\n')
                    character == 't' -> append('\t')
                    character == '\\' -> append('\\')
                    delimiter != null && character == delimiter -> append(character)
                    else -> append('\\').append(character)
                }
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else {
                append(character)
            }
        }
        if (escaped) append('\\')
    }
}

object AliasSuggestions {
    fun suggest(spelling: String): List<String> = spelling.trim().split(Regex("(?<=[a-z])(?=[A-Z])|\\s+|[-_]")).filter { it.length >= 2 }.distinct().take(5)
}

data class VocabularyPack(val id: String, val name: String, val terms: List<CustomTerm>, val enabled: Boolean = false)

object QuickAddRanking {
    fun rank(query: String, terms: List<CustomTerm>): List<CustomTerm> = terms.sortedWith(compareByDescending<CustomTerm> { it.spelling.equals(query, true) }.thenByDescending { it.spelling.startsWith(query, true) }.thenByDescending { it.usageCount }.thenByDescending { it.priority })
}
