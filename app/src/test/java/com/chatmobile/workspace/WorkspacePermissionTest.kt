package com.chatmobile.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **工作区权限判定**的契约测试。
 *
 * ## ★★★ 为什么这批测试必须存在
 *
 * 路径判定的错误**不会报错、不会崩溃，只会悄悄放行**。
 * 判错一个字符，模型就能读写工作区之外的任何地方 —— 而用户完全无感。
 * 这类 bug 只能靠测试钉住，真机演示永远发现不了。
 *
 * 特别针对三个真实存在的坑：
 * 1. **前缀相同但不是同一目录**：`/ws/abc` vs `/ws/abcd`
 * 2. **路径穿越**：`../other/file`
 * 3. **档位判定顺序**：先判操作种类，再判是否出界 —— 顺序错了错误信息会误导模型
 */
class WorkspacePermissionTest {

    private val root = "/data/user/0/com.chatmobile/files/workspaces/abc"

    // ================================================================
    // isInside
    // ================================================================

    @Test
    fun `工作区内的路径判定为在内`() {
        assertTrue(PermissionGuard.isInside("$root/notes.md", root))
        assertTrue(PermissionGuard.isInside("$root/sub/dir/a.txt", root))
    }

    @Test
    fun `根目录本身算在内`() {
        assertTrue(PermissionGuard.isInside(root, root))
    }

    /**
     * ★★ 这条是最容易写错的：裸 `startsWith` 会把 `abcd` 当成 `abc` 的子目录。
     * 一旦判错，`/workspaces/abcd` 就成了工作区 `abc` 的合法目标 —— 越权。
     */
    @Test
    fun `前缀相同但不是同一目录必须判为在外`() {
        assertFalse(
            "裸 startsWith 会把 /workspaces/abcd 误判成 /workspaces/abc 的子目录 —— 越权",
            PermissionGuard.isInside("/data/user/0/com.chatmobile/files/workspaces/abcd/x.txt", root),
        )
    }

    @Test
    fun `路径穿越必须被挡住`() {
        assertFalse(
            "模型可以轻易写出 ../ 去外面",
            PermissionGuard.isInside("$root/../../other/file", root),
        )
        assertFalse(
            PermissionGuard.isInside("$root/../sibling/file", root),
        )
    }

    @Test
    fun `反斜杠也当分隔符处理`() {
        // 模型可能从别处学到 Windows 风格路径
        assertTrue(PermissionGuard.isInside("$root\\notes.md", root))
    }

    @Test
    fun `空根路径必须判为在外而不是让所有路径通过`() {
        assertFalse(
            "root 为空时若返回 true，等于无限制放行",
            PermissionGuard.isInside("/etc/passwd", ""),
        )
    }

    // ================================================================
    // normalize
    // ================================================================

    @Test
    fun `规范化消掉点与多余斜杠`() {
        assertEquals("/a/b/c", PermissionGuard.normalize("/a//b/./c"))
        assertEquals("/a/c", PermissionGuard.normalize("/a/b/../c"))
        assertEquals("/", PermissionGuard.normalize("/"))
    }

    @Test
    fun `相对路径的穿越保留前导点点`() {
        // 相对路径消不掉 .. 时必须**保留**，否则穿越会变成"原地"
        assertEquals("../x", PermissionGuard.normalize("../x"))
    }

    // ================================================================
    // 三档权限 × 操作
    // ================================================================

    @Test
    fun `仅可查看档不能写和删`() {
        val p = WorkspacePermission.ReadOnly
        assertTrue(PermissionGuard.check(FileOperation.READ, "$root/a.md", root, p) is PermissionVerdict.Allowed)

        val write = PermissionGuard.check(FileOperation.WRITE, "$root/a.md", root, p)
        assertTrue("仅可查看档必须拒绝写", write is PermissionVerdict.Denied)

        val del = PermissionGuard.check(FileOperation.DELETE, "$root/a.md", root, p)
        assertTrue("仅可查看档必须拒绝删", del is PermissionVerdict.Denied)
    }

    @Test
    fun `工作区内修改档可以写但不能出界`() {
        val p = WorkspacePermission.WorkspaceWrite
        assertTrue(
            PermissionGuard.check(FileOperation.WRITE, "$root/a.md", root, p)
                is PermissionVerdict.Allowed,
        )
        assertTrue(
            "工作区内修改档不能碰工作区外",
            PermissionGuard.check(FileOperation.WRITE, "/data/other/x.md", root, p)
                is PermissionVerdict.Denied,
        )
    }

    @Test
    fun `完全权限档可以出界但仍受操作合法性约束`() {
        val p = WorkspacePermission.FullAccess
        assertTrue(
            PermissionGuard.check(FileOperation.WRITE, "/data/other/x.md", root, p)
                is PermissionVerdict.Allowed,
        )
    }

    @Test
    fun `拒绝理由必须可行动而不是只说 denied`() {
        val v = PermissionGuard.check(
            FileOperation.WRITE, "$root/a.md", root, WorkspacePermission.ReadOnly,
        ) as PermissionVerdict.Denied
        // 模型拿到 "denied" 会原样重试；说清档位和出路它才知道怎么办
        assertTrue("理由要提到当前档位", v.reason.contains("仅可查看"))
        assertTrue(
            "理由要指出出路（改设置）",
            v.reason.contains("按") || v.reason.contains("设置") || v.reason.contains("改成"),
        )
    }

    @Test
    fun `出界拒绝要给出工作区根路径`() {
        val v = PermissionGuard.check(
            FileOperation.WRITE, "/data/other/x.md", root, WorkspacePermission.WorkspaceWrite,
        ) as PermissionVerdict.Denied
        assertTrue(
            "必须告诉模型它该在哪里干活，否则它会一直换路径试探",
            v.reason.contains(root),
        )
    }

    // ================================================================
    // 档位语义
    // ================================================================

    @Test
    fun `默认档位是工作区内修改`() {
        assertEquals(WorkspacePermission.WorkspaceWrite, WorkspacePermission.DEFAULT)
        assertEquals(
            WorkspacePermission.WorkspaceWrite,
            WorkspacePermission.fromName("不存在的档位"),
        )
    }

    @Test
    fun `完全权限不等于什么都能干`() {
        // ★ 这条防的是"档位名 = 能力名"的误解：
        //   它只影响工作区边界，不影响工具开关、确认框、shell（我们压根没有）。
        val full = WorkspacePermission.FullAccess
        assertTrue(full.canWrite)
        assertTrue(full.canLeaveWorkspace)
        // 但它在工具集层面没有任何特殊豁免 —— 由 ChatToolExecutor 的 enabled 守门决定
    }
}
