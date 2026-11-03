package chatmobile.memory

/**
 * 记忆持久化接口。
 *
 * 刻意不依赖 Android / Room：核心逻辑要能脱离平台单测。
 * Android 层用 Room 实现它，测试层用内存实现。
 */
interface MemoryStore {
    fun all(): List<MemoryEntry>
    fun put(entry: MemoryEntry)
    fun remove(id: String)
}

/** 内存实现 —— 测试与预览用。 */
class InMemoryStore(initial: List<MemoryEntry> = emptyList()) : MemoryStore {
    private val map = LinkedHashMap<String, MemoryEntry>()

    init {
        initial.forEach { map[it.id] = it }
    }

    override fun all(): List<MemoryEntry> = map.values.toList()
    override fun put(entry: MemoryEntry) { map[entry.id] = entry }
    override fun remove(id: String) { map.remove(id) }

    fun size(): Int = map.size
}
