package com.chatmobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * **工作区** —— 一段对话可以绑定一个目录，模型在里面读写文件。
 *
 * ## ★★★ 两种工作区，本质不同
 *
 * | 类型 | 位置 | 模型怎么访问 | 用户怎么看到 |
 * |---|---|---|---|
 * | [Kind.PRIVATE] | App 私有目录 `files/workspaces/<id>` | **直接**读写 | 只能在 App 里看 |
 * | [Kind.SHARED] | 用户用 SAF 选的目录（下载/Documents） | 先写私有暂存区，**再推送过去** | 手机文件管理器里能看到 |
 *
 * ★ 为什么共享目录必须"先写私有、再推送"：
 *   SAF 的 `DocumentFile` 是**流式写入且不能随机访问**，模型需要"读-改-读"来回折腾。
 *   在私有目录里用真文件系统可以随便读写；写完一次推过去，语义简单得多，
 *   也避免模型把用户的目录写乱。**中间状态留在我们自己的地盘里。**
 *
 * ★ 为什么不直接申请 `MANAGE_EXTERNAL_STORAGE`（全盘文件访问）：
 *   那在 Play 上属高危权限、要专门审核，而且对一个聊天客户端是明显的过度授权。
 *   SAF 让**用户自己选**一个目录，权限范围正好等于他选的那个 —— 这是正确的边界。
 */
@Entity(
    tableName = "workspace",
    indices = [Index("updatedAt")],
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    /** 用户给工作区起的名字。空 = 用目录名兜底。 */
    val name: String,
    /** [Kind.PRIVATE] / [Kind.SHARED] */
    val kind: String,
    /**
     * SAF 授予的目录 URI（`content://…`）。
     *
     * ★ 只有 [Kind.SHARED] 才有值；私有工作区为 null（路径由 id 推导）。
     * ★ 这个 URI 必须配合 `takePersistableUriPermission` 用 ——
     *   不 take 的话进程重启后权限就没了（SAF 的经典坑）。
     */
    val treeUri: String? = null,
    /** 模型可写的相对范围提示（给提示词用，例如"只允许在 notes/ 下写"）。空 = 整个工作区。 */
    val scopeHint: String = "",
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_PRIVATE = "private"
        const val KIND_SHARED = "shared"
    }
}

/** 工作区类型（把字符串变成枚举，避免各处写魔法串）。 */
enum class WorkspaceKind {
    /** App 私有目录，模型直接读写。 */
    PRIVATE,

    /** 用户 SAF 选的目录，模型写暂存区后推送。 */
    SHARED,
    ;

    val persisted: String
        get() = when (this) {
            PRIVATE -> WorkspaceEntity.KIND_PRIVATE
            SHARED -> WorkspaceEntity.KIND_SHARED
        }

    companion object {
        fun fromPersisted(raw: String?): WorkspaceKind =
            if (raw == WorkspaceEntity.KIND_SHARED) SHARED else PRIVATE
    }
}
