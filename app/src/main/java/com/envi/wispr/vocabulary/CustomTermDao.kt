package com.envi.wispr.vocabulary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomTermDao {
    @Query("SELECT * FROM custom_terms ORDER BY priority DESC, spelling COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<CustomTermEntity>>

    @Query(
        "SELECT * FROM custom_terms WHERE spelling LIKE '%' || :query || '%' " +
            "OR aliases LIKE '%' || :query || '%' " +
            "OR COALESCE(category, '') LIKE '%' || :query || '%' " +
            "ORDER BY priority DESC, spelling COLLATE NOCASE ASC, id ASC",
    )
    suspend fun search(query: String): List<CustomTermEntity>

    @Query("SELECT * FROM custom_terms ORDER BY priority DESC, spelling COLLATE NOCASE ASC, id ASC")
    suspend fun list(): List<CustomTermEntity>

    @Query("SELECT COUNT(*) FROM custom_terms")
    suspend fun count(): Int

    @Query("SELECT * FROM custom_terms WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CustomTermEntity?

    @Query("SELECT * FROM custom_terms WHERE spelling = :spelling COLLATE BINARY LIMIT 1")
    suspend fun findExact(spelling: String): CustomTermEntity?

    @Query("SELECT * FROM custom_terms WHERE spelling = :spelling COLLATE NOCASE LIMIT 1")
    suspend fun findCaseInsensitive(spelling: String): CustomTermEntity?

    @Query("SELECT * FROM custom_terms WHERE spelling = :spelling COLLATE BINARY AND id != :excludedId LIMIT 1")
    suspend fun findExactExcluding(spelling: String, excludedId: Long): CustomTermEntity?

    @Query("SELECT * FROM custom_terms WHERE spelling = :spelling COLLATE NOCASE AND id != :excludedId LIMIT 1")
    suspend fun findCaseInsensitiveExcluding(spelling: String, excludedId: Long): CustomTermEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(term: CustomTermEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(term: CustomTermEntity): Int

    @Query("DELETE FROM custom_terms WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM custom_terms WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("UPDATE custom_terms SET usageCount = usageCount + :amount, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun incrementUsage(id: Long, amount: Long, updatedAtMs: Long): Int
}
