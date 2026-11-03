package com.chatmobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ChatMobile 的 Room 数据库。
 *
 * ## 版本与迁移策略
 *
 * 现在是 **v1**，schema 还没稳定（数据层刚落地）。
 * 这里**刻意不写** `fallbackToDestructiveMigration()`：
 * 那等于"以后每次改表都静默清空用户聊天记录"，而且**不会有人发现**——
 * 用户只会觉得"怎么记录都没了"。
 *
 * 正确做法：每次改 schema 就 +1 并写 [Migration]，迁移本身要有测试。
 * 骨架期还没数据，v1 就是起点。`exportSchema = true` 会把每个版本的 schema
 * 导到 `app/schemas/`，这是写迁移时的对照基准，也是**必须提交进仓库**的文件。
 *
 * ## 为什么 `exportSchema = true` 需要 `room.schemaLocation`
 * 不配的话 Room 编译期会**警告**（新版本是错误）。KSP 参数在 build 文件里，
 * 由 lead 统一管；配好后 schema 会落在 `app/schemas/com.chatmobile.data.ChatDatabase/`。
 * 我没动 build 文件——如果 lead 没配，这里会是一条编译警告，不影响运行。
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        CompressedSpanEntity::class,
        WorkspaceEntity::class,
    ],
    version = ChatDatabase.VERSION,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun memoryDao(): MemoryDao

    abstract fun compressedSpanDao(): CompressedSpanDao

    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        /** 改 schema 必须同步 +1，并在 [build] 里挂上对应的 Migration */
        const val VERSION = 5

        const val NAME = "chatmobile.db"

        /**
         * v1 → v2：新增 `compressed_spans` 表。
         *
         * ★ 这是**纯增量**迁移：只建新表，不碰已有的三张表。
         *   所以用户已有的对话、消息、记忆一条都不会动。
         *   （这也正是我们敢在没有备份机制的情况下升级的原因。）
         *
         * ★ DDL 必须和 Room 从 `CompressedSpanEntity` 生成的完全一致，
         *   否则运行时 `validateMigration` 会抛 `IllegalStateException`。
         *   ★★ 校验方法：跑一次 `:app:compileDebugKotlin`，看
         *   `app/schemas/com.chatmobile.data.ChatDatabase/2.json` 里的
         *   `createSql`，逐字对这里 —— **不要凭记忆写 DDL**。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `compressed_spans` (" +
                        "`id` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`fromMessageId` TEXT NOT NULL, " +
                        "`toMessageId` TEXT NOT NULL, " +
                        "`messageCount` INTEGER NOT NULL, " +
                        "`approxTokens` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_compressed_spans_conversationId` ON `compressed_spans` (`conversationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_compressed_spans_createdAt` ON `compressed_spans` (`createdAt`)",
                )
            }
        }

        /**
         * v2 → v3：`memory` 表新增 `key` 列（结构化记忆的槽位键）。
         *
         * ★ 纯增量：`ADD COLUMN` 不改任何已有行，旧记忆的 key 为 NULL，
         *   行为退回"自由文本 + 近义去重"，不会因为升级而丢东西。
         * ★ DDL 必须与 `app/schemas/.../3.json` 的 `createSql` 一致。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memory` ADD COLUMN `key` TEXT")
            }
        }

        /**
         * v3 → v4：新增 `workspace` 表 + `conversation.workspaceId` 列。
         *
         * ★ 纯增量（建新表 + ADD COLUMN），已有会话的 `workspaceId` 为 NULL
         *   ⇒ 全部落到「没有工作区」，行为与升级前完全一致。
         * ★ DDL **逐字照抄** `app/schemas/.../4.json` 的 `createSql`（把 `${TABLE_NAME}`
         *   换成真实表名）。凭记忆手写 DDL 会让 `validateMigration` 在运行时抛
         *   `IllegalStateException` —— 而那是在用户第一次打开 App 时才炸。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`treeUri` TEXT, " +
                        "`scopeHint` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_updatedAt` " +
                        "ON `workspace` (`updatedAt`)",
                )
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `workspaceId` TEXT")
            }
        }

        /**
         * v4 → v5：`conversation` 新增 `workspacePermission` 列。
         *
         * ★ 纯增量 `ADD COLUMN`。老会话该列为 NULL ⇒ 走 `WorkspacePermission.DEFAULT`，
         *   行为与升级前一致。
         * ★ DDL 逐字照抄 `app/schemas/.../5.json` 的 `createSql`。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `workspacePermission` TEXT")
            }
        }

        /**
         * 生产库。
         *
         * ★ 外键 CASCADE 要**显式打开**：SQLite 默认 `foreign_keys=OFF`。
         *   不开的话删会话不会连带删消息，会留下永久孤儿行。
         *   Room 的 `@ForeignKey` 只在建表时写进 DDL，**运行时是否生效取决于这个 pragma**。
         */
        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context.applicationContext, ChatDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /**
         * 内存库 —— 测试与预览用。
         *
         * ★ `setQueryExecutor`/`setTransactionExecutor` 都不用手动配成同步：
         *   Room 2.8 起内部用协程调度，`runInTransaction` 与 suspend DAO
         *   在 Robolectric 下能正常工作。
         */
        fun inMemory(context: Context): ChatDatabase =
            Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
