package chatmobile.tools

/**
 * **工具检索** —— 目录从「精确点名」升级为「自然语言检索」。
 *
 * ## ★★★ 为什么需要它（有据可依，不是我拍脑袋）
 *
 * 现在的目录是**全量罗列**：把所有懒加载工具的「名字 + 一句话」列在
 * `describe_tool` 的 description 里。10 个工具没问题，但：
 *
 * - Anthropic 官方明确：**工具数超过 30–50 个，模型选对的准确率开始下降**
 *   （`tool-search-tool` 文档原话："degrades once you exceed 30–50 available tools"）；
 * - 一旦 MCP 接进来，工具数轻易破百 ⇒ 目录会从"有用"变成"噪音"。
 *
 * ⇒ 工具上百之后，目录不能再靠罗列，必须**按当前问题检索**。
 *
 * ## ★★★ 为什么是 BM25，不是向量模型
 *
 * 这是本文件最重要的技术判断，依据是 **ToolRet**（ACL 2025，7,615 条检索任务 /
 * 43,215 个工具，且**不预先标注**相关工具）的实测数据：
 *
 * | 模型 | nDCG@10（带检索指令） |
 * |---|---|
 * | NV-Embed-v1（7B，最强） | 42.71 |
 * | **BM25s（纯词法）** | **36.46** |
 * | bge-base-en-v1.5 | 34.77 |
 * | e5-small-v2 | 31.14 |
 * | ColBERT | 23.79 |
 * | all-MiniLM-L6-v2（~22M） | 25.53 |
 *
 * 三个反直觉的结论，每个都直接影响选型：
 * 1. **BM25 不输甚至优于 ColBERT**（36.46 vs 23.79）；
 * 2. **能被端侧塞进去的小 embedding 模型反而不如 BM25**（25–31 vs 36）；
 * 3. 论文给的解释：工具查询与工具文档的 ROUGE-L 重叠只有 **0.06**（普通问答是 0.31），
 *    这个任务既不是"语义相似"也不是"字面匹配"，传统 IR 词法方法恰好是性价比最高的。
 *
 * ⇒ **端侧用 BM25 起步就够了，不必先上向量**。这也意味着它可以是**纯 Kotlin、
 *   零依赖、可完整单测**（本文件就是这样）。
 *
 * ## 为什么不复用它来做 `search_history`
 *
 * 那个是 SQL `LIKE` 子串匹配，数据在 Room 里；这里是内存里几十到几百条文档的排序。
 * 两者需求不同（前者要翻库、后者要排序），硬合并只会让两边都变差。
 *
 * ## 参数取值
 *
 * `k1 = 1.2` / `b = 0.75` 是 BM25 的经典默认值（论文与 Lucene 的常用取值），
 * 不是为本项目调出来的 —— 我**没有**为这个项目实测过最优值，所以不写"已调优"。
 */
object ToolSearch {

    /** BM25 词频饱和参数（经典默认值）。 */
    private const val K1 = 1.2

    /** BM25 文档长度归一化参数（经典默认值）。 */
    private const val B = 0.75

    /**
     * 检索一条工具文档。
     *
     * @param name 工具名（权重最高 —— 模型常常直接说名字）
     * @param oneLiner 一句话说明
     * @param description 完整描述（可为空，空则不参与）
     */
    data class Doc(
        val name: String,
        val oneLiner: String,
        val description: String = "",
    )

    /**
     * 检索。
     *
     * @param docs 候选工具（调用方已过滤掉 Disabled）
     * @param query 自然语言或关键词
     * @param limit 最多返回几条
     * @param minScore 低于它的不返回（查不到就返回空，好过返回一堆不相干的）
     * @return 命中的名字，**按相关度降序**；无命中返回空列表
     */
    fun search(
        docs: List<Doc>,
        query: String,
        limit: Int = 5,
        minScore: Double = 0.0,
    ): List<Scored> {
        val q = tokenize(query)
        if (q.isEmpty() || docs.isEmpty()) return emptyList()

        // 工具名权重更高：模型常常直接说「搜一下网页」这种近似名字的说法，
        // 而工具名本身是最强的信号。做法是把名字**重复三次**放进文档，
        // 比给每个字段单独设权重简单，效果一样。
        val tokenized = docs.map { d ->
            val text = buildString {
                append(d.name).append(' ').append(d.name).append(' ').append(d.name).append(' ')
                append(d.oneLiner)
                if (d.description.isNotEmpty()) append(' ').append(d.description)
            }
            tokenize(text)
        }

        val avgLen = tokenized.sumOf { it.size }.toDouble() / tokenized.size
        val n = docs.size

        // 文档频率（有 query 里的词才算，省一遍全表扫描）
        val df = HashMap<String, Int>()
        for (terms in tokenized) {
            val seen = HashSet<String>()
            for (t in terms) if (t in q && seen.add(t)) df[t] = (df[t] ?: 0) + 1
        }

        val scored = ArrayList<Scored>(docs.size)
        for (i in docs.indices) {
            val terms = tokenized[i]
            if (terms.isEmpty()) continue
            val tf = HashMap<String, Int>()
            for (t in terms) if (t in q) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) continue

            var score = 0.0
            for ((term, f) in tf) {
                val n_i = df[term] ?: 0
                if (n_i == 0) continue
                // BM25 IDF（加 1 防负值：出现次数超过一半的词在经典公式里会变成负数，
                // 那会让"含有该词"反而扣分 —— 对工具检索是很糟的行为）
                val idf = run {
                    val v = (n - n_i + 0.5) / (n_i + 0.5)
                    kotlin.math.ln(1.0 + v)
                }
                val norm = 1.0 - B + B * (terms.size / avgLen)
                score += idf * (f * (K1 + 1.0)) / (f + K1 * norm)
            }
            if (score > minScore) scored.add(Scored(docs[i].name, score))
        }

        return scored.sortedWith(
            // ★ 同分时按名字排 —— 结果必须**确定**：不确定的排序会让
            //   同一问题两次检索给出不同工具，模型的行为就随机了。
            compareByDescending<Scored> { it.score }.thenBy { it.name },
        ).take(limit)
    }

    /** 一条命中。 */
    data class Scored(val name: String, val score: Double)

    /**
     * 分词。
     *
     * ★ 刻意**保持简单**：小写 + 按非字母数字切分。
     *
     * 两个刻意的取舍：
     * 1. **不做英文词干化**（stemming）—— 工具名字段的匹配已经很直接，
     *    词干化会引入"search / searching / searched 全等价"这种噪声，
     *    而工具检索恰恰需要区分 `search_memory` 与 `search_history`。
     * 2. **中文按单字切**（不引入词典/分词器）—— 端侧不该为了工具检索
     *    背一个中文分词器。单字召回率够用，而精确度由 BM25 的 IDF 兜底。
     *    ★ 这条是**权衡**，不是最优解：工具描述是英文、工具名是英文，
     *      中文只在用户用中文描述需求时出现在 query 里。
     *
     * ★ 下划线/连字符**当分隔符**：`read_file` → `read` + `file`。
     *   这看着像"丢失信息"，实际是**增益** —— 用户说 "read a file" 时能命中 `read_file`。
     *   （实测：不切的话 "read a file" 与 `read_file` 零重叠，一条都搜不到。）
     */
    internal fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            when {
                ch.isLetterOrDigit() -> {
                    // 中日韩字符**单独成词**（每个字一个 token），
                    // 否则一整句中文会变成一个巨大的 token，永远匹配不上。
                    if (isCjk(ch)) {
                        if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                        out.add(ch.lowercaseChar().toString())
                    } else {
                        cur.append(ch.lowercaseChar())
                    }
                }
                else -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        // 单字符：英文的 "a" / "I" 是噪声，中文的 "搜" / "读" 是实义 ⇒ 只丢前者。
        return out.filter { t ->
            when {
                t.length > 1 -> t !in STOPWORDS
                else -> isCjkToken(t)
            }
        }
    }

    /**
     * 停用词。
     *
     * ★★★ 为什么必须有这个（这是一个实测出来的坑，不是理论上应该加）
     *
     * 第一版没有停用词表，结果查询 `"download a web page"` 会命中 `save_memory` ——
     * 因为它的描述里有 "…for **a** durable fact…"，那个 `a` 贡献了一点点分数，
     * 而只要分数 > 0 就被返回。
     *
     * 后果很实际：模型问"怎么下载网页"，却拿回来一个「记住这件事」的定义 ——
     * **比什么都没搜到更糟**，因为它会以为自己找对了。
     *
     * ⇒ 规则：英文**单字符与常见虚词**一律丢弃；中文单字**保留**（中文单字有实义）。
     */
    private val STOPWORDS = setOf(
        "a", "an", "the", "of", "to", "in", "on", "at", "for", "and", "or", "is", "are",
        "be", "it", "its", "this", "that", "these", "those", "i", "you", "we", "they",
        "do", "does", "did", "can", "should", "would", "will", "with", "by", "as", "from",
        "if", "then", "than", "so", "not", "no", "but", "my", "me", "your", "our",
        // 客套话也是纯噪声：用户/模型说 "please search it" 时，`please` 没有任何区分度。
        "please", "pls", "kindly", "just", "some", "any",
    )

    /** 单个 CJK 字符是有实义的（"搜" / "读"），不该被当停用词丢掉。 */
    private fun isCjkToken(t: String): Boolean = t.length == 1 && isCjk(t[0])

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // 中日韩统一表意文字
            (c in 0x3040..0x30FF) ||      // 日文假名
            (c in 0xAC00..0xD7AF)         // 韩文音节
    }
}
