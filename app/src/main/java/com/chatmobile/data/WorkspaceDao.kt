package com.chatmobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 工作区表的读写。 */
@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspace ORDER BY updatedAt DESC")
    suspend fun all(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspace WHERE id = :id")
    suspend fun byId(id: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WorkspaceEntity)

    @Query("DELETE FROM workspace WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE workspace SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)
}
