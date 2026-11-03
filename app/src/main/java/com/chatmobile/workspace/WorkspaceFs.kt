package com.chatmobile.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.chatmobile.data.WorkspaceKind
import java.io.File

/**
 * 工作区的**文件系统落地** —— 私有目录直写、SAF 目录先暂存再推送。
 *
 * ## ★★★ 为什么分两条路
 *
 * | 类型 | 落盘路径 | 模型读什么 | 用户在哪看 |
 * |---|---|---|---|
 * | 私有 | `files/workspaces/<id>/` | 真文件，随机读写 | 只能在 App 里 |
 * | 共享 | 先写 `files/workspaces/<id>/_staging/`，再 [push] 到 SAF | **暂存区的真文件** | 手机文件管理器 |
 *
 * ★ 关键设计：**模型永远只操作私有真文件**。
 *   SAF 的 `DocumentFile` 是流式、不能随机访问，而且某些 provider 对"读-改-写"
 *   支持极差。让模型直接在上面工作会踩一堆 provider 差异。
 *   ⇒ 让它在私有目录里正常干活，写完一次推过去。**中间状态留在我们自己的地盘。**
 *
 * ★ 为什么 `_staging` 而不是直接复用 `files/workspaces/<id>/`：
 *   共享工作区里，用户可能只希望**某些文件**被同步出去（比如只推 `notes/`）。
 *   有独立暂存区才能划清"这些是给用户的"和"这些是我的中间产物"。
 */
class WorkspaceFs(private val context: Context) {

    private val tag = "WorkspaceFs"

    /** 私有工作区根目录：`files/workspaces/<id>`。 */
    fun privateRoot(workspaceId: String): File =
        File(context.filesDir, "workspaces/$workspaceId").apply { mkdirs() }

    /**
     * 共享工作区的**暂存区**。
     *
     * ★ 不用 `cacheDir`：缓存可能被系统在低内存时清掉，而用户刚让模型写的东西
     *   不该因为我们看不见的回收而消失。
     */
    fun stagingRoot(workspaceId: String): File =
        File(privateRoot(workspaceId), "_staging").apply { mkdirs() }

    /**
     * 解析模型实际该读写哪个目录。
     *
     * ★ 两类工作区都返回**真实 File 路径** —— 这是"模型只操作真文件"的落点。
     *   共享工作区返回暂存区，私有工作区返回根目录。
     */
    fun workingDir(workspaceId: String, kind: WorkspaceKind): File =
        if (kind == WorkspaceKind.SHARED) stagingRoot(workspaceId) else privateRoot(workspaceId)

    // ---------------------------------------------------------------
    // 推送：暂存区 → SAF 目录
    // ---------------------------------------------------------------

    /**
     * 把暂存区里的文件推到用户选的 SAF 目录。
     *
     * @param treeUri 用户用 SAF 选中的目录（必须已 `takePersistableUriPermission`）
     * @return 推送结果摘要（成功/失败文件数 + 失败原因），**可直接喂给模型**
     *
     * ★ 逐文件 try：一个文件失败不该让整次推送白做 —— 剩下能推的照样推。
     * ★ 同名覆盖：用户让模型"更新 notes.md"，语义就是覆盖。
     *   ★ 但**只覆盖我们推过的那份**：目录里原本就有的同名文件会被覆盖 ——
     *     这一点必须告诉用户（见返回文本里的提示），不能悄悄干。
     */
    fun push(staging: File, treeUri: Uri): PushResult {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return PushResult(0, 0, listOf("无法打开所选目录（权限可能已失效，请重新选择）"))

        if (!tree.canWrite()) {
            return PushResult(0, 0, listOf("所选目录不可写"))
        }

        val files = staging.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return PushResult(0, 0, emptyList(), empty = true)

        var ok = 0
        val failures = mutableListOf<String>()

        for (f in files) {
            runCatching {
                // 找同名文件：有就覆盖内容，没有就新建
                val existing = tree.findFile(f.name)
                val target = existing ?: tree.createFile(mimeOf(f.name), f.name)
                    ?: error("无法在目标目录创建 ${f.name}")

                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    f.inputStream().use { input -> input.copyTo(out) }
                } ?: error("无法写入 ${f.name}")

                ok++
            }.onFailure { e ->
                Log.w(tag, "推送 ${f.name} 失败", e)
                failures += "${f.name}：${e.message ?: e::class.simpleName}"
            }
        }
        return PushResult(ok, failures.size, failures)
    }

    /**
     * 简单 MIME 推断。
     *
     * ★ 只覆盖 AI 真的会写的类型。`application/octet-stream` 作为兜底 ——
     *   大多数 SAF provider 接受它，而猜错类型比不猜更糟（比如把 md 标成 image）。
     */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "xml" -> "text/xml"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------
    // 列出 / 清理
    // ---------------------------------------------------------------

    /** 工作区里的文件清单（相对路径 + 大小），用于设置页展示与提示词描述。 */
    fun listFiles(root: File, maxEntries: Int = 50): List<WorkspaceFile> {
        val out = mutableListOf<WorkspaceFile>()
        root.walkTopDown()
            .onEnter { it.name != "_staging" || it == root }
            .filter { it.isFile }
            .take(maxEntries)
            .forEach { f ->
                out += WorkspaceFile(
                    // ★ trimStart 要 Char，`File.separatorChar` 是 Char —— 但 removePrefix 里
                    //   用的是 String。这里统一按 Char 处理，避免 Int/CharSequence 不匹配。
                    relativePath = f.absolutePath.removePrefix(root.absolutePath)
                        .trimStart(File.separatorChar),
                    sizeBytes = f.length(),
                )
            }
        return out
    }

    /** 清空工作区目录（删工作区时用）。 */
    fun wipe(workspaceId: String): Boolean =
        runCatching { File(context.filesDir, "workspaces/$workspaceId").deleteRecursively() }
            .getOrDefault(false)

    /**
     * 检查 SAF 权限是否还活着。
     *
     * ★ **必须显式检查**：`takePersistableUriPermission` 给的授权会因
     *   用户清理数据、卸载重装、或系统回收而消失，而 `DocumentFile` 那时
     *   只会"看起来能打开但写不进去"。不检查的话表现为**静默推送失败**。
     */
    fun hasPersistedPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    /** 从 URI 里尽量抠出人类可读的目录名（给工作区起名字用）。 */
    fun guessDisplayName(treeUri: Uri): String {
        return runCatching {
            DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifBlank { "工作区" }
        }.getOrDefault("工作区")
    }
}

/** 推送结果。 */
data class PushResult(
    val pushed: Int,
    val failed: Int,
    val failures: List<String>,
    val empty: Boolean = false,
) {
    /**
     * 渲染成给模型读的一段话。
     *
     * ★ 这是**工具结果**，会直接进模型的上下文。所以它必须说清三件事：
     *   推了几个、失败几个（为什么）、以及**"覆盖了目标目录里的同名文件"这个事实** ——
     *   用户需要知道自己的文件被改动了。
     */
    fun render(targetName: String): String = buildString {
        if (empty) {
            append("暂存区里没有文件，什么都没推送（工作区「").append(targetName).append("」未改动）。")
            return@buildString
        }
        append("已推送到「").append(targetName).append("」：成功 ")
        append(pushed).append(" 个")
        if (failed > 0) {
            append("，失败 ").append(failed).append(" 个")
        }
        append("。")
        if (failures.isNotEmpty()) {
            append("\n失败明细：\n")
            failures.forEach { append("- ").append(it).append('\n') }
        }
        if (pushed > 0) {
            append("\n注意：目标目录中原有的同名文件已被覆盖。")
        }
    }.trimEnd()
}

/** 工作区里的一个文件。 */
data class WorkspaceFile(
    val relativePath: String,
    val sizeBytes: Long,
)
