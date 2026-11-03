package com.chatmobile.workspace

/**
 * **模型对工作区的权限档位** —— 对齐 DSH 的三档。
 *
 * | 档位 | 读 | 写 | 删 | 出工作区 |
 * |---|---|---|---|---|
 * | [ReadOnly] 仅可查看 | ✅ | ❌ | ❌ | ❌ |
 * | [WorkspaceWrite] 工作区内修改 | ✅ | ✅ | ✅ | ❌ |
 * | [FullAccess] 完全权限 | ✅ | ✅ | ✅ | ✅（仍受工具集与确认框约束） |
 *
 * ## ★★★ 为什么必须是一个**独立的强制层**，而不是"提示词里写别乱改"
 *
 * 提示词是**请求**，不是**约束**。模型完全可能（也经常）不照做 ——
 * 尤其在被用户反复要求、或上下文里出现过旧工具定义时。
 * 真正的边界只能落在**执行器**里：每一次文件操作都过一遍档位判定，
 * 越权就返回结构化的拒绝结果（喂回给模型，让它自己收场）。
 *
 * 这与本项目已经确立的 `ChatToolExecutor` 里那道 `enabled` 守门是同一个思路：
 * **用户关掉的东西必须真的关掉，不能只靠模型自觉。**
 *
 * ## ★ 「完全权限」不等于"什么都能干"
 *
 * 它是"可以出工作区"，但仍然：
 * - 受工具集开关约束（用户在设置里关掉的工具照样不能调）
 * - 受 `requiresConfirmation` 约束（有副作用的操作要确认）
 * - **不能执行 shell**（我们压根没提供这个工具）
 *
 * ★ 这一点必须写进提示词，否则模型会以为档位名 = 能力名。
 */
enum class WorkspacePermission(val label: String, val description: String) {

    ReadOnly(
        label = "仅可查看",
        description = "模型只能读工作区里的文件，不能新建、修改或删除任何东西。",
    ),

    WorkspaceWrite(
        label = "工作区内修改",
        description = "模型可以在工作区里新建、修改、删除文件，但走不出这个工作区。",
    ),

    FullAccess(
        label = "完全权限",
        description = "模型可以读写工作区之外的路径（仍受工具开关与确认框约束，且不能执行命令）。",
    ),
    ;

    val canRead: Boolean get() = true

    val canWrite: Boolean
        get() = this != ReadOnly

    val canDelete: Boolean
        get() = this != ReadOnly

    /** 能否访问工作区之外的路径。 */
    val canLeaveWorkspace: Boolean
        get() = this == FullAccess

    companion object {
        fun fromName(raw: String?): WorkspacePermission =
            entries.firstOrNull { it.name == raw } ?: WorkspaceWrite

        /**
         * 默认档位 = [WorkspaceWrite]。
         *
         * ★ 为什么不默认 [ReadOnly]：用户建工作区就是为了让模型**干活**，
         *   默认只读等于每建一个都要再点一次放开，反而容易让人无脑点"完全权限"。
         * ★ 为什么不默认 [FullAccess]：那是把边界一次交出去。
         *   中间档是"能用 + 出不去"，这与沙箱的共识一致。
         */
        val DEFAULT: WorkspacePermission = WorkspaceWrite
    }
}

/**
 * 一次文件操作的**判定结果**。
 *
 * ★ 拒绝时要给出**人能懂且模型能行动**的理由 —— 只说 "denied" 模型会原样重试，
 *   说清"当前档位是仅可查看，请让用户去设置里放开"它才知道该怎么办。
 */
sealed interface PermissionVerdict {
    data object Allowed : PermissionVerdict

    data class Denied(val reason: String) : PermissionVerdict
}

/** 文件操作种类。 */
enum class FileOperation { READ, WRITE, DELETE }

/**
 * **权限判定** —— 纯函数，可完整单测。
 *
 * @param targetPath 模型想操作的**绝对路径**
 * @param workspaceRoot 当前工作区根目录的**绝对路径**（私有根 或 共享暂存区）
 */
object PermissionGuard {

    /**
     * @return [PermissionVerdict.Allowed] 或 [PermissionVerdict.Denied]（带可行动的理由）
     */
    fun check(
        operation: FileOperation,
        targetPath: String,
        workspaceRoot: String,
        permission: WorkspacePermission,
    ): PermissionVerdict {
        // ① 先按操作种类判
        when (operation) {
            FileOperation.READ -> if (!permission.canRead) {
                return PermissionVerdict.Denied(
                    "当前权限档位是「${permission.label}」，不允许读取。",
                )
            }
            FileOperation.WRITE -> if (!permission.canWrite) {
                return PermissionVerdict.Denied(
                    "当前权限档位是「${permission.label}」，不能新建或修改文件。" +
                        "请告诉用户需要把工作区权限改成「工作区内修改」或「完全权限」。",
                )
            }
            FileOperation.DELETE -> if (!permission.canDelete) {
                return PermissionVerdict.Denied(
                    "当前权限档位是「${permission.label}」，不能删除文件。" +
                        "请告诉用户去设置里放开权限，不要反复尝试。",
                )
            }
        }

        // ② 再按"是否在工作区内"判
        if (!permission.canLeaveWorkspace && !isInside(targetPath, workspaceRoot)) {
            return PermissionVerdict.Denied(
                "路径 `$targetPath` 在工作区之外。当前档位是「${permission.label}」，" +
                    "只能在 `$workspaceRoot` 里面操作。具体该怎么做：" +
                    "把文件放到工作区里处理，或者告诉用户需要「完全权限」。",
            )
        }

        return PermissionVerdict.Allowed
    }

    /**
     * 判断 `target` 是否在 `root` 之内（**含路径穿越防护**）。
     *
     * ★ 为什么不用 `startsWith` 字符串比较：
     *   `/data/workspaces/abc` 与 `/data/workspaces/abcd` 前缀相同但不是同一个目录，
     *   裸 startsWith 会把后者误判成"在里面"。
     * ⇒ 比较前补上分隔符。
     *
     * ★ 为什么必须处理 `..`：
     *   模型可以轻易写出 `../other/file`。规范化之后再比，
     *   否则穿越检查形同虚设。
     */
    fun isInside(target: String, root: String): Boolean {
        val t = normalize(target)
        val r = normalize(root)
        if (r.isEmpty()) return false
        return t == r || t.startsWith(r + "/") || t.startsWith(r + "\\")
    }

    /**
     * 规范化路径：统一分隔符、消掉 `.` 与 `..`、去掉多余斜杠。
     *
     * ★ 自己实现而不是用 `File.canonicalPath`：后者会**访问文件系统**
     *   （解析符号链接、检查存在性），在纯函数判定里既慢又可能因文件不存在而行为不同。
     *   这里只需要**词法**规范化，那正是安全判定需要的层级。
     */
    fun normalize(path: String): String {
        val unified = path.replace('\\', '/').trim()
        if (unified.isEmpty()) return ""
        val absolute = unified.startsWith("/")
        val out = ArrayDeque<String>()
        for (seg in unified.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast() else {
                    if (!absolute) out.addLast("..")
                }
                else -> out.addLast(seg)
            }
        }
        val body = out.joinToString("/")
        return if (absolute) "/$body" else body
    }
}
