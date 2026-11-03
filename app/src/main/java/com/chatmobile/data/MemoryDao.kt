package com.chatmobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 记忆 DAO。
 *
 * 注意这里**没有** `weight` 相关列或查询 —— 权重是时间的函数，
 * 必须每次由 `chatmobile.memory.Decay` 现算。理由见 [MemoryEntity] 类注释。
 */
@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Query("SELECT * FROM memory")
    suspend fun all(): List<MemoryEntity>

    /** 默认视图：不含墓碑。与 `MemoryBank.list()` 的默认语义一致 */
    @Query("SELECT * FROM memory WHERE deleted = 0")
    suspend fun allAlive(): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun findById(id: String): MemoryEntity?

    @Query("SELECT * FROM memory WHERE kind = :kind AND deleted = 0")
    suspend fun byKind(kind: String): List<MemoryEntity>

    /** 内容去重查询 —— `MemoryBank.recordRecent` 的合并逻辑要它（大小写不敏感） */
    @Query(
        """
        SELECT * FROM memory
        WHERE deleted = 0 AND content = :content COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun findAliveByContent(content: String): MemoryEntity?

    /** 物理删除。**只有**用户"彻底删除"才走到这里；`MemoryBank.delete` 走的是墓碑 */
    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun physicalDelete(id: String): Int

    @Query("DELETE FROM memory")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM memory WHERE deleted = 0")
    suspend fun aliveCount(): Int

    /** 响应式：记忆一变就推送（设置页显示条数用） */
    @Query("SELECT * FROM memory WHERE deleted = 0")
    fun observeAlive(): Flow<List<MemoryEntity>>

    /**
     * 全量替换 —— 给"缓存回写"用的一次性对齐。
     *
     * 在一个事务里做，避免中途失败留下半新半旧的库。
     */
    @Transaction
    suspend fun replaceAll(memories: List<MemoryEntity>) {
        clear()
        upsertAll(memories)
    }
}
