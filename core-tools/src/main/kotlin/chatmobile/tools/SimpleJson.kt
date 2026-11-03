package chatmobile.tools

/**
 * 极简 JSON 解析器 —— 只为解析工具调用参数。
 *
 * 为什么不用 kotlinx.serialization：
 *   core-tools 要保持零依赖，这样能直接塞进任何工程（含 Android）。
 *   而且我们只需要解析「一层扁平的对象，值全是字符串」，用不着完整实现。
 *
 * 支持的输入形态：
 *   {"command":"sed","args":"s/a/b/\u0000file.txt"}
 *   {"command":"grep","args":"-n\u0000pattern\u0000file.txt"}
 *
 * ★ 故意不支持嵌套对象/数组 —— 参数就是扁平的。
 *   如果模型给了嵌套结构，这里会返回空 map，执行器会报「缺少 command」，
 *   模型看到错误后会用正确格式重试。这比写一个半吊子 JSON 解析器安全。
 */
object SimpleJson {

    fun parseObjectOfStrings(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val s = json.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw IllegalArgumentException("不是 JSON 对象")
        }

        var i = 1
        val end = s.length - 1
        while (i < end) {
            i = skipWhitespaceAndCommas(s, i, end)
            if (i >= end) break
            if (s[i] != '"') throw IllegalArgumentException("第 $i 个字符处期望键的引号")

            val (key, afterKey) = readString(s, i, end)
            i = skipWhitespace(s, afterKey, end)
            if (i >= end || s[i] != ':') throw IllegalArgumentException("键 '$key' 后缺少冒号")
            i = skipWhitespace(s, i + 1, end)

            if (i >= end) throw IllegalArgumentException("键 '$key' 缺少值")

            if (s[i] == '"') {
                val (value, afterValue) = readString(s, i, end)
                result[key] = value
                i = afterValue
            } else {
                // 数字 / 布尔 / null —— 统一按字面量存成字符串
                val start = i
                while (i < end && s[i] != ',' && s[i] != '}') i++
                result[key] = s.substring(start, i).trim()
            }
        }
        return result
    }

    private fun skipWhitespace(s: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && s[i].isWhitespace()) i++
        return i
    }

    private fun skipWhitespaceAndCommas(s: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && (s[i].isWhitespace() || s[i] == ',')) i++
        return i
    }

    /** 读取一个带转义处理的字符串，返回 (内容, 结束引号后的位置) */
    private fun readString(s: String, quoteAt: Int, end: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = quoteAt + 1
        while (i < end) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' && i + 1 < end -> {
                    i++
                    when (val esc = s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 4 >= end) throw IllegalArgumentException("\\u 转义不完整")
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)   // 未知转义按原样保留
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        throw IllegalArgumentException("字符串没有闭合引号")
    }
}
