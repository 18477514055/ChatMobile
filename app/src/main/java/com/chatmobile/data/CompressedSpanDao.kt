package com.chatmobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 压缩记录的读写。
 *
 * ★ 没有 `@Update` —— 压缩记录是**追加型事实**（"这一段在某个时刻被折过"），
 *   不该被改写。要改就删了重压。
 */
@Dao
interface CompressedSpanDao {

    /** 某会话的全部压缩记录，按时间正序（渲染历史时要按发生顺序排） */
    @Query("SELECT * FROM compressed_spans WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun forConversation(conversationId: String): List<CompressedSpanEntity>

    /** 条数（诊断 / 测试用） */
    @Query("SELECT COUNT(*) FROM compressed_spans WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompressedSpanEntity)

    @Query("DELETE FROM compressed_spans WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}
