package com.chatmobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 会话 DAO。
 *
 * ## 列表排序：`pinned DESC, updatedAt DESC`
 *
 * 置顶的排在最前；同一组内按最后活动时间倒序。
 * 这条 SQL 有对应的复合索引 `(archived, pinned, updatedAt)`，见 [ConversationEntity]。
 *
 * ★ 为什么不做成 `sortedBy` 让调用方自己排：
 *   会话可能有几百条，而列表是**每次回前台都要拉**的。让 SQLite 走索引，
 *   比把 500 行读进内存再排序省一个数量级的 IO。
 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity)

    /** 整条覆盖（用于"从备份/分支复制一个会话"这类场景） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    /**
     * 侧边栏主查询。
     *
     * ★ 这里**没有**默认参数值（`= false`）。Room 的 DAO 方法一律写全参数，
     *   默认值放在 [ChatRepository] 那一层 —— DAO 是 SQL 映射，不该有"便利重载"，
     *   而且 Room 对 DAO 方法上的 Kotlin 默认参数支持并不完整，容易在生成代码时炸。
     */
    @Query(
        """
        SELECT * FROM conversation
        WHERE (:includeArchived = 1 OR archived = 0)
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    suspend fun list(includeArchived: Boolean): List<ConversationEntity>

    /** 响应式版本：会话表一变，UI 自动收到新列表 */
    @Query(
        """
        SELECT * FROM conversation
        WHERE (:includeArchived = 1 OR archived = 0)
        ORDER BY pinned DESC, updatedAt DESC
        """,
    )
    fun observe(includeArchived: Boolean): Flow<List<ConversationEntity>>

    /** 只改标题。`updatedAt` 不动 —— 重命名不该让会话跳到列表顶部 */
    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String): Int

    /** 归档 / 取消归档。同样不动 `updatedAt`。 */
    @Query("UPDATE conversation SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean): Int

    /** 置顶 / 取消置顶。同样不动 `updatedAt`。 */
    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean): Int

    /** 记录一次活动（新消息、收到回复）。这是**唯一**会推 `updatedAt` 的操作 */
    @Query("UPDATE conversation SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long): Int

    @Query("UPDATE conversation SET modelId = :modelId WHERE id = :id")
    suspend fun setModelId(id: String, modelId: String?): Int

    @Query("UPDATE conversation SET systemPromptOverride = :prompt WHERE id = :id")
    suspend fun setSystemPromptOverride(id: String, prompt: String?): Int

    /**
     * 切换工作区。
     *
     * ★ 传 null = 解除绑定。
     * ★ **只改工作区，不动 updatedAt** —— 用户换个目录不该让这条会话跳到侧边栏顶部
     *   （它不是"有新内容"，只是改了设置）。
     */
    @Query("UPDATE conversation SET workspaceId = :workspaceId WHERE id = :id")
    suspend fun setWorkspaceId(id: String, workspaceId: String?): Int

    /** 切换本会话的工作区权限档位。 */
    @Query("UPDATE conversation SET workspacePermission = :permission WHERE id = :id")
    suspend fun setWorkspacePermission(id: String, permission: String?): Int

    /** 删除会话。消息由外键 CASCADE 连带删掉，不需要（也不应该）手动删两遍。 */
    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM conversation")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversation")
    suspend fun count(): Int
}
