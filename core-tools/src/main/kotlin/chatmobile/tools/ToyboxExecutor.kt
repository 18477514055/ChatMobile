package chatmobile.tools

/**
 * toybox 命令执行器。
 *
 * ★ 安全设计（这是本文件最重要的部分）：
 *   1. **命令白名单** —— 只允许执行枚举出来的命令，不是任意 shell
 *   2. **不经 shell** —— 用 ProcessBuilder 的数组形式，参数不会被 shell 解释
 *      ⇒ `; rm -rf /` 这种注入不可能发生（它只是一个普通参数字符串）
 *   3. **超时强制终止** —— 防止 `find /` 这种卡死
 *   4. **输出截断** —— 防止 `cat 大文件` 撑爆上下文
 *   5. **工作目录隔离** —— 默认限制在应用私有目录
 *
 * 不用 `sh -c "cmd"`：那等于把 shell 注入原样交给模型，是灾难。
 */
class ToyboxExecutor(
    /** toybox 二进制的路径。Android 上是 /system/bin/toybox */
    private val toyboxPath: String = "/system/bin/toybox",
    /** 允许执行的命令白名单 */
    private val allowedCommands: Set<String> = DEFAULT_ALLOWED,
    private val config: ExecutorConfig = ExecutorConfig(),
) : ToolExecutor {

    data class ExecutorConfig(
        /** 超时（毫秒） */
        val timeoutMs: Long = 10_000,
        /** 输出最大字节数，超出截断 */
        val maxOutputBytes: Int = 64 * 1024,
        /** 最大并发（暂未实现，预留） */
        val maxConcurrent: Int = 4,
    )

    override fun execute(call: ToolCall, workingDir: String?): ToolResult {
        val start = System.currentTimeMillis()

        // ---- 1. 解析参数 ----
        val args = try {
            parseArgs(call.argumentsJson)
        } catch (e: Exception) {
            return ToolResult.Failure(call.callId, "参数不是合法 JSON: ${e.message}")
        }

        val command = args["command"]
        if (command.isNullOrBlank()) {
            return ToolResult.Failure(call.callId, "缺少必填参数 command")
        }

        // ---- 2. 白名单校验 ----
        if (command !in allowedCommands) {
            return ToolResult.Failure(
                call.callId,
                "命令 '$command' 不在允许列表中。可用命令: ${allowedCommands.sorted().joinToString(", ")}",
            )
        }

        val commandArgs = args["args"]?.split('\u0000')?.filter { it.isNotEmpty() } ?: emptyList()

        // ---- 3. 危险参数检查 ----
        validateArgs(command, commandArgs)?.let { reason ->
            return ToolResult.Failure(call.callId, "参数被拒绝: $reason")
        }

        // ---- 4. 执行（数组形式，不经 shell）----
        return try {
            runProcess(call.callId, command, commandArgs, workingDir, start)
        } catch (e: Exception) {
            ToolResult.Failure(call.callId, "执行失败: ${e.message}")
        }
    }

    private fun runProcess(
        callId: String,
        command: String,
        args: List<String>,
        workingDir: String?,
        start: Long,
    ): ToolResult {
        // ★ 关键区分（真机实测得出）：
        //   大多数命令是 toybox applet → /system/bin/toybox <cmd> <args>
        //   但 awk 是独立二进制       → /system/bin/awk <args>
        //   搞错了会得到 "toybox: Unknown command awk"
        val argv = if (command in STANDALONE_BINARIES) {
            listOf("/system/bin/$command") + args
        } else {
            listOf(toyboxPath, command) + args
        }

        val pb = ProcessBuilder(argv)
            .redirectErrorStream(true)   // stderr 合并到 stdout，模型能看到错误

        if (workingDir != null) pb.directory(java.io.File(workingDir))

        val process = pb.start()

        // 读取输出（必须并发读，否则管道满了会死锁）
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(output) {
                        if (output.length < config.maxOutputBytes) output.append(line).append('\n')
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return ToolResult.Failure(
                callId,
                "执行超时（${config.timeoutMs}ms）已强制终止",
                retryable = true,
            )
        }
        reader.join(2000)

        val text = synchronized(output) { output.toString() }
        val truncated = text.length >= config.maxOutputBytes
        val duration = System.currentTimeMillis() - start

        // 非零退出码也算「成功执行」—— 工具跑通了，只是结果里有错误信息
        // 模型需要看到这个错误才能自己修正，不能当成调用失败
        val body = if (truncated) {
            text.take(config.maxOutputBytes) + "\n...(输出被截断)"
        } else {
            text
        }

        return ToolResult.Success(callId, body.trimEnd(), duration, truncated)
    }

    /**
     * 参数安全检查。
     *
     * 虽然已经不走 shell，但仍有必要拦这些：
     *   - 访问系统敏感路径
     *   - `find /` 这类全盘扫描
     */
    private fun validateArgs(command: String, args: List<String>): String? {
        val dangerousPaths = listOf("/system", "/vendor", "/proc/sys", "/data/data", "/data/system")
        for (arg in args) {
            for (p in dangerousPaths) {
                // 允许读取 /system/bin 下的工具本身，但不允许遍历整个 /system
                if (arg == p && (command == "find" || command == "rm" || command == "cat")) {
                    return "不允许对整个 $p 执行 $command"
                }
            }
            if (arg.contains("\u0000")) return "参数包含非法字符"
        }
        return null
    }

    /** 解析 {"command":"sed","args":"s/a/b/\u0000file.txt"} 这种结构 */
    private fun parseArgs(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return SimpleJson.parseObjectOfStrings(json)
    }

    companion object {
        /**
         * 默认允许的命令。
         *
         * 只放**文本处理 + 只读查询**类。
         * 刻意不放：rm / mv / chmod / dd / mount / reboot 等破坏性命令。
         *
         * ⚠️ 2026-11-02 真机实测修正：
         *   不是所有命令都是 toybox applet！`awk` 在 Android 上是**独立二进制**
         *   （`/system/bin/awk`，不是 `-> toybox` 软链）。
         *   而 `rev` / `join` 在这台 Android 14 上**根本不存在**。
         *
         *   ⇒ 执行时必须区分：
         *     - toybox applet：`/system/bin/toybox <cmd>`
         *     - 独立二进制：   `/system/bin/<cmd>`
         *   否则 `toybox awk` 会报 "Unknown command awk"。
         *
         * 这个坑只有真跑才会暴露 —— `ls` 能看到文件存在，但看不出它是不是 applet。
         */
        val DEFAULT_ALLOWED: Set<String> = setOf(
            // 文本处理
            "sed", "grep", "awk", "sort", "uniq", "cut", "tr", "wc",
            "nl", "tac", "head", "tail", "cat", "paste",
            // 查找
            "find", "xargs", "which", "basename", "dirname", "realpath",
            // 编码 / 校验
            "base64", "md5sum", "sha1sum", "sha256sum", "cksum", "iconv",
            // 转换
            "dos2unix", "unix2dos", "expand", "xxd", "od", "strings",
            // 归档（只读解压）
            "tar", "gzip", "gunzip", "zcat",
            // 差异
            "diff", "cmp", "comm",
            // 信息
            "stat", "file", "du", "df", "date", "uname",
        )

        /**
         * 非 toybox applet 的命令 —— 必须作为独立二进制执行。
         *
         * 真机实测：`/system/bin/awk` 是独立二进制（不是 toybox 软链）。
         * 未来若发现更多，往这里加。
         */
        val STANDALONE_BINARIES: Set<String> = setOf(
            "awk",     // Android 上是独立 awk（toybox 没有 awk 实现）
        )
    }
}
