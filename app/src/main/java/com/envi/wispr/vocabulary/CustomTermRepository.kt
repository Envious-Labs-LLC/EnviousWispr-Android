package com.envi.wispr.vocabulary

import android.content.Context
import androidx.room.withTransaction
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.settings.CustomWordsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DuplicateCustomTermException(spelling: String) : IllegalArgumentException("A custom term already exists for '$spelling'")

data class CustomTermImportResult(val added: Int, val updated: Int, val skipped: Int, val rejected: Int)

/** Transaction boundary for structured vocabulary mutations and legacy import. */
class CustomTermRepository(private val database: EnviousWisprDatabase) {
    constructor(context: Context) : this(EnviousWisprDatabase.get(context))

    private val dao: CustomTermDao = database.customTermDao()

    fun observe(): Flow<List<CustomTermRecord>> = dao.observeAll().map { rows ->
        rows.map(CustomTermEntity::toRecord).sortedBy(CustomTermRecord::id)
    }
    fun observeTerms(): Flow<List<CustomTerm>> = observe().map { rows -> rows.map(CustomTermRecord::term) }
    suspend fun list(): List<CustomTermRecord> = dao.list().map(CustomTermEntity::toRecord).sortedBy(CustomTermRecord::id)
    suspend fun search(query: String): List<CustomTermRecord> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return dao.list().map(CustomTermEntity::toRecord).filter { record ->
            record.term.spelling.contains(needle, ignoreCase = true) ||
                record.term.aliases.any { it.contains(needle, ignoreCase = true) } ||
                record.term.category?.contains(needle, ignoreCase = true) == true
        }
    }

    suspend fun add(term: CustomTerm, nowMs: Long = System.currentTimeMillis()): CustomTermRecord {
        val normalized = normalizeForStorage(term)
        return database.withTransaction {
            requireTermCapacity(1)
            requireAliasCapacity(normalized.aliases.size)
            ensureNoCollision(normalized)
            val id = dao.insert(CustomTermEntity.fromCustomTerm(normalized, nowMs))
            dao.findById(id)?.toRecord() ?: error("Inserted custom term disappeared")
        }
    }

    suspend fun edit(id: Long, term: CustomTerm, nowMs: Long = System.currentTimeMillis()): CustomTermRecord? {
        val normalized = normalizeForStorage(term)
        return database.withTransaction {
            val existing = dao.findById(id) ?: return@withTransaction null
            requireAliasCapacity(normalized.aliases.size, removed = existing.toCustomTerm().aliases.size)
            ensureNoCollision(normalized, id)
            val updated = CustomTermEntity.fromCustomTerm(
                normalized.copy(usageCount = existing.usageCount),
                nowMs,
                id,
            ).copy(createdAtMs = existing.createdAtMs)
            if (dao.update(updated) != 1) return@withTransaction null
            updated.toRecord()
        }
    }

    suspend fun delete(id: Long): Boolean = database.withTransaction { dao.deleteById(id) == 1 }

    suspend fun bulkDelete(ids: Collection<Long>): Int {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return 0
        return database.withTransaction { dao.deleteByIds(distinctIds) }
    }

    suspend fun incrementUsage(id: Long, amount: Long = 1, nowMs: Long = System.currentTimeMillis()): Boolean {
        require(amount > 0) { "Usage increment must be positive" }
        return database.withTransaction { dao.incrementUsage(id, amount, nowMs) == 1 }
    }

    /** Applies one validated import plan in a single Room transaction. */
    suspend fun applyImport(
        preview: ImportPreview,
        nowMs: Long = System.currentTimeMillis(),
    ): CustomTermImportResult = database.withTransaction {
        val currentRows = dao.list()
        val occupiedSpellings = currentRows.mapTo(mutableListOf()) { it.spelling }
        val accepted = preview.accepted.map { candidate ->
            normalizeForStorage(candidate.copy(imported = true))
        }
        val additions = accepted.filter { candidate ->
            if (occupiedSpellings.any { it.equals(candidate.spelling, ignoreCase = true) }) {
                false
            } else {
                occupiedSpellings += candidate.spelling
                true
            }
        }
        val replacements = if (preview.replaceCollisions) {
            preview.collisions.mapNotNull { candidate ->
                val normalized = normalizeForStorage(candidate.copy(imported = true))
                currentRows.firstOrNull { existing ->
                    existing.spelling.equals(normalized.spelling, ignoreCase = true)
                }?.let { existing -> existing to normalized }
            }
        } else {
            emptyList()
        }

        requireTermCapacity(additions.size)
        requireAliasCapacity(
            additional = additions.sumOf { it.aliases.size } + replacements.sumOf { it.second.aliases.size },
            removed = replacements.sumOf { it.first.toCustomTerm().aliases.size },
            currentRows = currentRows,
        )

        additions.forEach { candidate ->
            dao.insert(CustomTermEntity.fromCustomTerm(candidate, nowMs))
        }
        replacements.forEach { (existing, normalized) ->
            val entity = CustomTermEntity.fromCustomTerm(normalized, nowMs, existing.id)
                .copy(createdAtMs = existing.createdAtMs)
            check(dao.update(entity) == 1) { "Custom term changed during import" }
        }

        CustomTermImportResult(
            added = additions.size,
            updated = replacements.size,
            skipped = accepted.size - additions.size +
                if (preview.replaceCollisions) preview.collisions.size - replacements.size else preview.collisions.size,
            rejected = preview.rejected,
        )
    }

    /** Imports old newline/comma vocabulary once, retaining it as imported terms. */
    suspend fun migrateLegacySharedPreferences(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val preferences = context.applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(LEGACY_MIGRATION_MARKER, false)) return 0
        val legacyWords = CustomWordsStore(context).load()
        val imported = database.withTransaction {
            var remaining = (MAX_TERMS - dao.count()).coerceAtLeast(0)
            val occupiedSpellings = dao.list().mapTo(mutableListOf()) { it.spelling }
            legacyWords.count { word ->
                if (remaining == 0) return@count false
                val normalized = normalizeForStorage(CustomTerm(word, imported = true))
                if (occupiedSpellings.any { it.equals(normalized.spelling, ignoreCase = true) }) {
                    false
                } else {
                    dao.insert(CustomTermEntity.fromCustomTerm(normalized, nowMs))
                    occupiedSpellings += normalized.spelling
                    remaining--
                    true
                }
            }
        }
        // A crash before marker write is safe: rerun sees the inserted rows and skips them.
        preferences.edit().putBoolean(LEGACY_MIGRATION_MARKER, true).commit()
        return imported
    }

    private suspend fun ensureNoCollision(term: CustomTerm, excludedId: Long = 0L) {
        val collision = dao.list().any { existing ->
            existing.id != excludedId && existing.spelling.equals(term.spelling, ignoreCase = true)
        }
        if (collision) {
            throw DuplicateCustomTermException(term.spelling)
        }
    }

    private suspend fun requireAliasCapacity(
        additional: Int,
        removed: Int = 0,
        currentRows: List<CustomTermEntity>? = null,
    ) {
        require(additional >= 0 && removed >= 0) { "Alias count cannot be negative" }
        val current = (currentRows ?: dao.list()).sumOf { it.toCustomTerm().aliases.size }
        require(current - removed + additional <= MAX_TOTAL_ALIASES) {
            "Custom vocabulary cannot exceed $MAX_TOTAL_ALIASES total aliases"
        }
    }

    private suspend fun requireTermCapacity(additional: Int) {
        require(additional >= 0) { "Additional term count cannot be negative" }
        require(dao.count() + additional <= MAX_TERMS) {
            "Custom vocabulary cannot exceed $MAX_TERMS terms"
        }
    }

    private fun normalizeForStorage(term: CustomTerm): CustomTerm {
        require(term.aliases.size <= MAX_ALIASES_PER_TERM) { "Too many aliases" }
        val normalized = term.normalized().copy(category = term.category?.trim()?.takeIf(String::isNotEmpty))
        require(normalized.spelling.isNotEmpty()) { "Custom term spelling cannot be blank" }
        require(normalized.spelling.length <= MAX_STRING_LENGTH) { "Custom term spelling is too long" }
        require(normalized.aliases.all { it.length <= MAX_STRING_LENGTH }) { "Custom term alias is too long" }
        require(normalized.category?.length?.let { it <= MAX_STRING_LENGTH } != false) {
            "Custom term category is too long"
        }
        require(normalized.minSimilarityOverride?.let { it.isFinite() && it in 0.0..1.0 } != false) {
            "Match strictness must be between 0 and 1"
        }
        require(normalized.usageCount >= 0L) { "Usage count cannot be negative" }
        return normalized
    }

    private companion object {
        const val MAX_TERMS = 2_000
        const val MAX_TOTAL_ALIASES = 2_000
        const val MAX_STRING_LENGTH = 200
        const val MAX_ALIASES_PER_TERM = 256
        const val LEGACY_PREFERENCES = "envious_wispr_settings"
        const val LEGACY_MIGRATION_MARKER = "custom_terms_migrated_v1"
    }
}
