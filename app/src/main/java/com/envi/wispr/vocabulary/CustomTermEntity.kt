package com.envi.wispr.vocabulary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custom_terms",
    indices = [Index(value = ["spelling"]), Index(value = ["category"]), Index(value = ["priority"])],
)
data class CustomTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spelling: String,
    /** Escaped unit-separated aliases remain searchable while preserving arbitrary text. */
    val aliases: String,
    val category: String?,
    val priority: Int,
    val forceReplace: Boolean,
    val caseSensitive: Boolean,
    val minSimilarityOverride: Double?,
    val usageCount: Long,
    val imported: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    fun toCustomTerm(): CustomTerm = CustomTerm(
        spelling = spelling,
        aliases = decodeAliases(aliases),
        category = category,
        priority = priority,
        forceReplace = forceReplace,
        caseSensitive = caseSensitive,
        minSimilarityOverride = minSimilarityOverride,
        usageCount = usageCount,
        imported = imported,
    )

    companion object {
        fun fromCustomTerm(term: CustomTerm, nowMs: Long, id: Long = 0): CustomTermEntity {
            val normalized = term.normalized()
            return CustomTermEntity(
                id = id,
                spelling = normalized.spelling,
                aliases = encodeAliases(normalized.aliases),
                category = normalized.category?.trim()?.takeIf(String::isNotEmpty),
                priority = normalized.priority,
                forceReplace = normalized.forceReplace,
                caseSensitive = normalized.caseSensitive,
                minSimilarityOverride = normalized.minSimilarityOverride,
                usageCount = normalized.usageCount.coerceAtLeast(0),
                imported = normalized.imported,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        }

        private const val SEPARATOR = '\u001F'
        private const val ESCAPED_SEPARATOR = "\\u001F"

        private fun encodeAliases(aliases: List<String>): String = aliases.joinToString(SEPARATOR.toString()) { alias ->
            alias.replace("\\", "\\\\").replace(SEPARATOR.toString(), ESCAPED_SEPARATOR)
        }

        private fun decodeAliases(encoded: String): List<String> {
            if (encoded.isEmpty()) return emptyList()
            val aliases = mutableListOf<String>()
            val current = StringBuilder()
            var index = 0
            while (index < encoded.length) {
                when {
                    encoded[index] == SEPARATOR -> {
                        aliases += current.toString()
                        current.setLength(0)
                        index++
                    }
                    encoded[index] == '\\' && encoded.startsWith(ESCAPED_SEPARATOR, index) -> {
                        current.append(SEPARATOR)
                        index += ESCAPED_SEPARATOR.length
                    }
                    encoded[index] == '\\' && index + 1 < encoded.length -> {
                        current.append(encoded[index + 1])
                        index += 2
                    }
                    else -> current.append(encoded[index++])
                }
            }
            aliases += current.toString()
            return aliases
        }
    }
}

data class CustomTermRecord(
    val id: Long,
    val term: CustomTerm,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

fun CustomTermEntity.toRecord(): CustomTermRecord = CustomTermRecord(id, toCustomTerm(), createdAtMs, updatedAtMs)
