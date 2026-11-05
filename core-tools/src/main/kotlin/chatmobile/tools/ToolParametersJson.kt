package chatmobile.tools

/**
 * 把 [ToolParameters] 编码成 JSON Schema 字符串 —— **唯一实现**。
 *
 * ## ★★★ 为什么单独抽出来
 *
 * 这份编码原本写在 `ToolRegistry.toOpenAiToolsJson()` 里面，与"拼整个 tools 数组"
 * 混在一起。引入 MCP 的 `rawParametersJson` 之后就有**两条路**：
 *
 * ```
 * 结构化参数  → ToolParametersJson.encode()  → 发出去
 * 原样透传    → rawParametersJson            → 发出去
 * ```
 *
 * 若编码只留在 `ToolRegistry` 里，那么 `describeTool`（懒加载召回那条路）
 * 就没法复用它 —— 而那正是 `L26` 的形状：**同一个数据、两个消费点，各写一份**。
 *
 * ## 三条纪律（都是踩过坑之后加的）
 *
 * 1. **`additionalProperties: false` 必须写** —— 少写它，模型会往参数里塞
 *    schema 没声明的字段，严格的网关直接 400。
 * 2. **属性顺序 = `properties` 的插入顺序** —— `LinkedHashMap` 保住它。
 *    顺序变了，缓存前缀就变了（Anthropic 逐字节比对前缀）。
 * 3. **不输出任何动态内容** —— 不能有时间戳、不能有"当前有几个工具"。
 */
object ToolParametersJson {

    /**
     * 编码一份结构化参数。
     *
     * 输出形如：
     * `{"type":"object","properties":{...},"required":[...],"additionalProperties":false}`
     */
    fun encode(parameters: ToolParameters): String {
        val sb = StringBuilder()
        sb.append("""{"type":"object","properties":{""")
        parameters.properties.entries.forEachIndexed { i, (pname, pspec) ->
            if (i > 0) sb.append(',')
            sb.append(ToolRegistry.jsonString(pname)).append(":{")
            sb.append(""""type":""").append(ToolRegistry.jsonString(pspec.type.jsonName))
            sb.append(""","description":""").append(ToolRegistry.jsonString(pspec.description))
            if (pspec.items != null) {
                // ★ 用 jsonString() 包住**整个类型名**（它会自带一对引号）——
                //   绝不能手写一半引号：`"type":"` + 裸值 + `"` 这种写法漏一个引号
                //   就是非法 JSON（本轮实际踩过，产出 `{"type":string"}`
                //   与 `{"type":"string""}` 两种错法）。
                //   ★ 判据由 ToolTableJsonTest 的「真的解析一遍」兜底。
                sb.append(""","items":{"type":""")
                    .append(ToolRegistry.jsonString(pspec.items.jsonName)).append("}")
            }
            if (pspec.enum != null) {
                sb.append(""","enum":[""")
                pspec.enum.forEachIndexed { j, v ->
                    if (j > 0) sb.append(',')
                    sb.append(ToolRegistry.jsonString(v))
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append('}')
        if (parameters.required.isNotEmpty()) {
            sb.append(""","required":[""")
            parameters.required.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.append(ToolRegistry.jsonString(r))
            }
            sb.append(']')
        }
        // ★ 必须有：禁止模型塞未声明的参数。
        sb.append(""","additionalProperties":false""")
        sb.append('}')
        return sb.toString()
    }
}
