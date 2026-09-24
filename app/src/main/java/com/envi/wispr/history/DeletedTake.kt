package com.envi.wispr.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A take whose words the user deleted (#288): its row, or all History while the take was live. Written in the same
 * transaction as the delete, and read in the same transaction as any insert for the take, so a late save or a recovery
 * never brings deleted words back and a delete that fails leaves no mark. Holds the id only, never a word. Kept for
 * good: one short row per deleted take.
 */
@Entity(tableName = "deleted_takes")
internal data class DeletedTake(@PrimaryKey val takeId: String)
