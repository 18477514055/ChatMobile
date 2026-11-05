package com.chatmobile.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **确认框的触发判据**。
 *
 * ## ★★★ 这批测试守的是用户的一句原话
 *
 * > 「如果模型每次改动文件都要问我，会不会让任务断的很难受？只读才需要吧，
 * >   工作区权限是工作区改不用，工作区外用，完全权限是不用任何确认，
 * >   此外注意任何读取都不用审批。」
 *
 * 这条决定的是**体验**，不是安全：每次写文件都弹一个框，一个"帮我整理这十个文件"
 * 的任务会变成点十次确认 —— 任务被切碎，用户会直接关掉工具不用了。
 *
 * 而反过来，如果判据写错成"永远不问"，那写工作区外的文件就没人拦了。
 * ⇒ 两个方向都要钉。
 */
class ConfirmPolicyTest {

    // ---------------------------------------------------------------
    // ★ 任何读取都不用审批
    // ---------------------------------------------------------------

    @Test
    fun `任何读取在任何档位都不确认`() {
        for (perm in WorkspacePermission.entries) {
            for (inside in listOf(true, false)) {
                assertFalse(
                    "档位 $perm（工作区内=$inside）下，读取也不该弹确认框 —— " +
                        "用户原话「任何读取都不用审批」",
                    perm.requiresConfirmation(FileOperation.READ, inside),
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // ★ 工作区内写不用确认
    // ---------------------------------------------------------------

    @Test
    fun `工作区内写不用确认`() {
        for (perm in listOf(WorkspacePermission.WorkspaceWrite, WorkspacePermission.FullAccess)) {
            assertFalse(
                "档位 $perm 下，工作区内的写入不该弹确认框 —— " +
                    "用户原话「工作区权限是工作区改不用」。每次都问会让任务断得很难受",
                perm.requiresConfirmation(FileOperation.WRITE, insideWorkspace = true),
            )
            assertFalse(
                "档位 $perm 下，工作区内删除同样不确认",
                perm.requiresConfirmation(FileOperation.DELETE, insideWorkspace = true),
            )
        }
    }

    // ---------------------------------------------------------------
    // ★ 完全权限：什么都不问
    // ---------------------------------------------------------------

    @Test
    fun `完全权限对任何写都不确认`() {
        for (op in FileOperation.entries) {
            for (inside in listOf(true, false)) {
                assertFalse(
                    "FullAccess（工作区内=$inside，操作=$op）应当**任何操作都不确认** —— " +
                        "用户原话「完全权限是不用任何确认」",
                    WorkspacePermission.FullAccess.requiresConfirmation(op, inside),
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // ★ 工作区外写：要问
    // ---------------------------------------------------------------

    @Test
    fun `工作区外写在写档位下要确认`() {
        assertTrue(
            "WorkspaceWrite 档下，工作区外的写入必须确认 —— 这是这条链路唯一该问的情况。" +
                "（当前它其实已被 PermissionGuard 先拒，但判据本身必须是对的）",
            WorkspacePermission.WorkspaceWrite
                .requiresConfirmation(FileOperation.WRITE, insideWorkspace = false),
        )
    }

    /**
     * ReadOnly 档：写操作由**权限层直接拒绝**，确认层不该再问。
     *
     * ★ 我一开始把这条写成"只读档至少要走到确认这一步"，跑出来是红的 ——
     *   而**红的是尺子，不是实现**。
     *
     *   理由：确认框的意义是"让用户决定要不要放行"。而在 ReadOnly 档下，
     *   无论用户点多少次"允许"，`PermissionGuard` 都会拒绝写入。
     *   弹一个"允许也没有用"的框，比不弹更糟：用户会以为是自己点得不对。
     *
     * ⇒ 正确语义是**拒绝要早于询问**，而不是绕一圈先问再拒。
     */
    @Test
    fun `只读档的写操作用拒绝兜底而不是询问`() {
        assertFalse(
            "ReadOnly 档下写操作不该弹确认框 —— 权限层会直接拒绝，" +
                "问一次再拒只是白要点一下",
            WorkspacePermission.ReadOnly
                .requiresConfirmation(FileOperation.WRITE, insideWorkspace = true),
        )
        // ★ 真正该守的是这一条：拒绝必须真的发生，而且理由要说清
        val verdict = PermissionGuard.check(
            operation = FileOperation.WRITE,
            targetPath = "/data/ws/a/notes.md",
            workspaceRoot = "/data/ws/a",
            permission = WorkspacePermission.ReadOnly,
        )
        assertTrue("ReadOnly 档写工作区内文件必须被拒绝", verdict is PermissionVerdict.Denied)
        val reason = (verdict as PermissionVerdict.Denied).reason
        assertTrue(
            "拒绝理由必须说清「当前档位」并指导怎么办 —— " +
                "只说 denied 模型会原样重试",
            reason.contains("仅可查看") && reason.contains("权限"),
        )
    }

    // ---------------------------------------------------------------
    // 权限层与确认层的方向必须一致
    // ---------------------------------------------------------------

    @Test
    fun `权限层与确认层不矛盾`() {
        // 工作区内写：权限层放行，确认层不问 ⇒ 一条顺滑的路径
        val allowed = PermissionGuard.check(
            operation = FileOperation.WRITE,
            targetPath = "/data/ws/a/notes.md",
            workspaceRoot = "/data/ws/a",
            permission = WorkspacePermission.WorkspaceWrite,
        )
        assertTrue("工作区内写必须被权限层放行", allowed is PermissionVerdict.Allowed)
        assertFalse(
            "既然权限层放行了，确认层就不该再问 —— 否则就是「放行了还要打断你」",
            WorkspacePermission.WorkspaceWrite
                .requiresConfirmation(FileOperation.WRITE, insideWorkspace = true),
        )
    }
}
