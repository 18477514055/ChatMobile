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
        description = "模型能读工作区里的文件，但不能新建、修改或删除任何东西。读取永远不弹确认框。",
    ),

    WorkspaceWrite(
        label = "工作区内修改",
        description = "模型可以自由读写在**这个工作区里**的文件，不打断你确认。" +
            "只有动到工作区之外时才会问你。",
    ),

    FullAccess(
        label = "完全权限",
        description = "读、写、改工作区内外的文件都不弹确认框（仍受工具开关与不可执行命令的限制）。",
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

    /**
     * 这个档位下，**文件操作要不要弹确认框**。
     *
     * ## ★★★ 这一条是本档位定义的核心（用户原话）
     *
     * > 「如果模型每次改动文件都要问我，会不会让任务断的很难受？只读才需要吧，
     * >   工作区权限是工作区改不用，工作区外用，完全权限是不用任何确认，
     * >   此外注意任何读取都不用审批。」
     *
     * 拆成四条：
     * | 情况 | 要不要确认 |
     * |---|---|
     * | **任何读取** | **永远不用**（不论档位） |
     * | 工作区内写 | **不用** |
     * | 工作区外写（`workspace-write` 档） | **要**（其实权限层会直接拒绝，见下） |
     * | `full-access` 档 | **任何写都不用** |
     *
     * ## ★ 为什么"工作区外写"在这一档其实走不到确认框
     *
     * `WorkspaceWrite` 档下，`PermissionGuard` 会**直接拒绝**工作区外的写入
     * （`canLeaveWorkspace = false`）。所以它根本不会进入"要不要确认"这一步 ——
     * 拒绝比确认更早发生。
     *
     * ⚠️ 但**仍然要把它算成"需要确认"**：将来若有人放宽了拦截（比如改成"先问再放行"），
     * 这里要能自动跟上，而不是悄悄变成"直接写"。
     * 这正是「守卫要写在两处、且两侧语义一致」的落点。
     */
    fun requiresConfirmation(
        operation: FileOperation,
        insideWorkspace: Boolean,
    ): Boolean = when {
        // ① 读取永远不确认 —— 不论档位、不论在不在工作区里
        operation == FileOperation.READ -> false
        // ② 只读档：写操作由 PermissionGuard **直接拒绝**，不该先问一遍。
        //    弹一个"允许也没有用"的框比不弹更糟 —— 用户会以为是自己点得不对。
        this == ReadOnly -> false
        // ③ 完全权限：任何写都不问
        this == FullAccess -> false
        // ④ 工作区内写：不问（这正是用户说的"工作区改不用"）
        insideWorkspace -> false
        // ⑤ 工作区外写：要问（当前档位下其实已被 PermissionGuard 先拒）
        else -> true
    }

    /**
     * 写进**运行时事实**的那一串 —— 必须与 `SystemPrompt` 里的字面值逐字一致。
     *
     * ## ★★★ 为什么必须逐字一致
     *
     * 提示词里写的是：
     * ```
     * - `read-only` — ...
     * - `workspace-write` — ...
     * - `full-access` — ...
     * ```
     * 并且明确交代「你的档位去运行时事实那块读」。如果这里写的是
     * 「仅可查看」这种中文标签，模型拿到的是一个它在提示词里**从没见过**的词
     * ⇒ 它只能猜怎么解释，而猜错的代价是**越权尝试或该做的不敢做**。
     *
     * ⇒ 两侧共用同一组字符串常量（见 [PROMPT_VALUES]），改一处必须改两处。
     */
    val promptValue: String
        get() = when (this) {
            ReadOnly -> "read-only"
            WorkspaceWrite -> "workspace-write"
            FullAccess -> "full-access"
        }

    /**
     * 附在档位后面的一句提醒 —— 让模型**不只看档位名，还知道它的边界在哪**。
     *
     * ★ 「完全权限」那一档尤其需要这句：它的名字听起来像"什么都能干"，
     *   而实际上它只放开了工作区边界，工具集与确认框照样管着。
     */
    val promptHint: String
        get() = when (this) {
            ReadOnly -> "you may read only; creating, modifying or deleting files will be rejected"
            WorkspaceWrite -> "you may create, modify and delete files inside this directory only"
            FullAccess ->
                "you may also read and write outside this directory, but the tool switches " +
                    "and confirmation prompts still apply, and you cannot run shell commands"
        }

    companion object {
        fun fromName(raw: String?): WorkspacePermission =
            entries.firstOrNull { it.name == raw } ?: WorkspaceWrite

        /**
         * 从**提示词字面值**（`read-only` / `workspace-write` / `full-access`）反查档位。
         *
         * ★ 为什么需要它：`RuntimeFacts.WorkspaceInfo` 里带的是送给模型的那一串，
         *   而执行层判档要用枚举。两者之间必须有一个**唯一**的转换入口 ——
         *   否则每处都手写 `when (str) {...}`，改一处漏一处，
         *   最后就是「提示词说能写、执行器说不能写」这种前后不一致。
         *
         * ★ 认不出时回退 [DEFAULT]（工作区内修改），**不是**回退 [FullAccess]：
         *   认不出就往宽的方向猜，等于把边界交出去。
         */
        fun fromPromptValue(raw: String?): WorkspacePermission =
            entries.firstOrNull { it.promptValue == raw } ?: DEFAULT

        /** 三档的提示词字面值 —— 契约测试用它跟 `SystemPrompt` 逐字比对。 */
        val PROMPT_VALUES: List<String> = entries.map { it.promptValue }

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
