package chatmobile.memory

/**
 * 记忆写入时的"同一件事"判定。
 *
 * ★★★ 为什么必须有这个文件（真机实测出来的问题）：
 *   模型报出「同一件事在注入里重复 9 遍」。读码发现 `recordRecent` 已有
 *   `content.equals(..., ignoreCase = true)` 的去重 —— 但它只认**字面完全相同**。
 *   抽取器每 6 条跑一次、窗口重叠一半，面对重叠输入会产出**措辞有微小差异的同一批事实**
 *   （「迭代固定提示词」/「固定提示词迭代」），字面比较全部漏掉。
 *
 *   ⇒ 需要一个**与写法无关**的相似判定，且**不能依赖向量模型**：
 *     中文用字符 bigram，英文/数字用归一化后的字符 bigram；纯 CPU、微秒级。
 *
 * ★ 这里只做"判定"，不做"合并策略" —— 合并放哪一条、hitCount 怎么加，
 *   属于 [MemoryBank] 的职责，纯函数不该知道存储结构。
 */
object Dedupe {

    /**
     * 归一化：全角转半角、小写、去标点、压空白。
     *
     * ★ 为什么要去掉标点再算 bigram：
     *   「迭代固定提示词。」和「迭代固定提示词」应该判为同一条；
     *   标点会让 bigram 集合多出两个无关项，把 Jaccard 拉低。
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text) {
            val c = when {
                raw == '\u3000' -> ' '
                raw in '\uFF01'..'\uFF5E' -> (raw.code - 0xFEE0).toChar() // 全角 ASCII
                else -> raw
            }
            // 只保留：字母 / 数字 / CJK；其余（标点、空白、emoji）一律视为分隔
            val keep = c.isLetterOrDigit() || isCjk(c)
            if (keep) sb.append(c.lowercaseChar()) else sb.append(' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** 归一化后的"紧凑串"：去掉所有空白，用于 bigram 与包含判断。 */
    fun blob(text: String): String = normalize(text).replace(" ", "")

    /**
     * 字符 bigram 集合。
     *
     * ★ 中文不做分词：字符 bigram 对中文的召回质量在短文本上已经够用，
     *   而且不需要任何词典/分词库（Android 上省一个依赖）。
     */
    fun bigrams(text: String): Set<String> {
        val b = blob(text)
        if (b.isEmpty()) return emptySet()
        if (b.length == 1) return setOf(b)
        return buildSet {
            for (i in 0 until b.length - 1) add(b.substring(i, i + 2))
        }
    }

    /** 集合 Jaccard 相似度；任一为空返回 0。 */
    fun jaccard(a: String, b: String): Double {
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.count { it in sb }
        val union = sa.size + sb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * 两条记忆是否在说"同一件事"。
     *
     * 规则按顺序：
     *  1. 归一化后完全相同 ⇒ 是
     *  2. 一方是另一方的子串、且较短一方 ≥ [MIN_CONTAIN_LEN] 字符 ⇒ 是
     *     （"迭代固定提示词" ⊂ "迭代固定提示词的顺序"）
     *  3. 较短一方 < [SHORT_LEN] ⇒ 要求 Jaccard ≥ [SHORT_THRESHOLD]（防"喜欢猫/喜欢狗"被合并）
     *  4. 其余 ⇒ Jaccard ≥ [threshold]
     */
    fun isSameTopic(
        a: String,
        b: String,
        threshold: Double = 0.7,
        shortThreshold: Double = 0.95,
        shortLen: Int = 6,
        minContainLen: Int = 5,
    ): Boolean {
        val ba = blob(a)
        val bb = blob(b)
        if (ba.isEmpty() || bb.isEmpty()) return false
        if (ba == bb) return true

        val shorter = if (ba.length <= bb.length) ba else bb
        val longer = if (ba.length <= bb.length) bb else ba
        if (shorter.length >= minContainLen && longer.contains(shorter)) return true

        val j = jaccard(a, b)
        return if (shorter.length < shortLen) j >= shortThreshold else j >= threshold
    }

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF ||   // 基本汉字
            c.code in 0x3400..0x4DBF ||   // 扩展 A
            c.code in 0xF900..0xFAFF ||   // 兼容汉字
            c.code in 0x3040..0x30FF      // 日文假名（用户可能混用）
}
