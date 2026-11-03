package com.chatmobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话表。
 *
 * ★ 为什么 `id` 用 String(UUID) 而不是自增 Long：
 *   会话 ID 会进 URL 式分享、导出文件、错误日志。自增 ID 在删除后会**被复用**——
 *   旧日志里的 "会话 12" 可能指向一条完全不相干的新会话。UUID 不会。
 *
 * ★ `systemPromptOverride` 是对 MVP 方案 §2.1 的**补齐**：
 *   方案里写了它（"本会话覆盖的指令"），但骨架模型里漏了。
 *   自定义指令是官方行为的一部分（见可行性报告 §3），现在不留位置，
 *   将来加就是一次需要 migration 的破坏性改动。
 */
@Entity(
    tableName = "conversation",
    indices = [
        Index("updatedAt"),
        // 侧边栏查询走 (archived, pinned, updatedAt)：这是唯一的高频列表查询
        Index(value = ["archived", "pinned", "updatedAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** 本会话用的模型 ID；null = 跟随全局设置 */
    val modelId: String? = null,
    /** 本会话覆盖的 system prompt；null = 跟随全局设置 */
    val systemPromptOverride: String? = null,
    /**
     * 本会话绑定的工作区 id；**null = 没有工作区**。
     *
     * ★ 三态语义（侧边栏按它分类）：
     *   - 有值 ⇒ 归到那个工作区
     *   - null 且用户从未设过默认 ⇒ 「没有工作区」
     *   - null 但用户设过默认工作区 ⇒ 侧边栏显示在「默认工作区」下
     *     （默认工作区是**全局设置**，不是往每条会话里写一个 id —— 否则改默认值时
     *      要批量更新所有历史会话，而且分不清"我特意选的"和"当时是默认"）
     */
    val workspaceId: String? = null,
    /**
     * 本会话的工作区**权限档位**（`WorkspacePermission.name`）。
     *
     * ★ 为什么挂在会话上而不是全局：同一台手机里，
     *   「随手问两句」的对话和「让它整理我下载目录」的对话，权限要求完全不同。
     *   全局一个档位必然要么太松要么太紧。
     *
     * ★ null = 用默认档（[WorkspacePermission.DEFAULT]）。存 null 而不是存默认值，
     *   是为了让"以后改默认档"能对已有会话生效。
     */
    val workspacePermission: String? = null,
)
