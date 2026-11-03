package chatmobile.tools

/**
 * 内置工具集 —— MVP 阶段够用的那几个。
 *
 * 设计原则：**少而精**。
 * 给模型 20 个工具它反而不会用；给 5 个边界清晰的，它知道什么时候该用哪个。
 */
object BuiltinTools {

    /** 通用命令执行 —— 最灵活，也最需要小心 */
    val shell = ToolSpec(
        name = "run_command",
        description = """
            执行一条文本处理命令并返回输出。
            适用于：查找、替换、过滤、统计、格式化、编码转换、解压查看等。
            不支持：网络请求、安装软件、修改系统。
            命令必须来自允许列表（sed/grep/awk/sort/uniq/cut/tr/wc/find/base64/sha256sum 等）。
            参数用 \u0000 分隔（注意不是空格）。
        """.trimIndent(),
        parameters = ToolParameters(
            properties = mapOf(
                "command" to ParamSpec(
                    ParamType.STRING,
                    "要执行的命令名，例如 sed / grep / awk / sort",
                ),
                "args" to ParamSpec(
                    ParamType.STRING,
                    "命令参数，多个参数用 \\u0000 分隔。例如 sed 的 's/a/b/\\u0000file.txt'",
                ),
            ),
            required = listOf("command", "args"),
        ),
    )

    /** 读取文件内容 —— 比 cat 更可控（带行数限制） */
    val readFile = ToolSpec(
        name = "read_file",
        description = """
            读取一个文本文件的内容。
            默认最多读 200 行，可用 maxLines 调整。
            用于查看用户提供的文件、日志、配置等。
        """.trimIndent(),
        parameters = ToolParameters(
            properties = mapOf(
                "path" to ParamSpec(ParamType.STRING, "文件的绝对路径"),
                "maxLines" to ParamSpec(ParamType.INTEGER, "最多读取多少行，默认 200"),
            ),
            required = listOf("path"),
        ),
    )

    /** 列出目录 —— 先看有什么，再决定读哪个 */
    val listDir = ToolSpec(
        name = "list_dir",
        description = "列出目录下的文件和子目录。用于先了解结构，再决定读哪个文件。",
        parameters = ToolParameters(
            properties = mapOf(
                "path" to ParamSpec(ParamType.STRING, "目录的绝对路径"),
            ),
            required = listOf("path"),
        ),
    )

    /** 文本统计 —— 最常用的「看一眼」操作 */
    val textStats = ToolSpec(
        name = "text_stats",
        description = """
            统计文本的基本信息：字符数、行数、词数。
            用于快速判断文本规模，决定后续怎么处理。
        """.trimIndent(),
        parameters = ToolParameters(
            properties = mapOf(
                "path" to ParamSpec(ParamType.STRING, "文本文件的绝对路径"),
            ),
            required = listOf("path"),
        ),
    )

    /** 正则替换 —— 最高频的实际需求 */
    val regexReplace = ToolSpec(
        name = "regex_replace",
        description = """
            对文本做正则查找替换，返回替换后的内容（不修改原文件）。
            用于批量改写、格式统一、清理脏数据。
        """.trimIndent(),
        parameters = ToolParameters(
            properties = mapOf(
                "path" to ParamSpec(ParamType.STRING, "源文件的绝对路径"),
                "pattern" to ParamSpec(ParamType.STRING, "正则表达式（sed 语法）"),
                "replacement" to ParamSpec(ParamType.STRING, "替换成的文本，支持 & 表示整个匹配"),
            ),
            required = listOf("path", "pattern", "replacement"),
        ),
    )

    /** 全部内置工具 */
    fun all(): List<ToolSpec> = listOf(shell, readFile, listDir, textStats, regexReplace)
}
