package com.codeforge.plugin.idea.diff

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * 多文件修改队列管理器
 *
 * 解决的问题：
 *   Agent 调用多次 write_file 时，旧方案每次立即弹 InlineDiff，
 *   5 个文件 = 5 次弹窗打断，完全破坏 Agent 执行流。
 *
 * 解决方案：
 *   Agent 任务开始 → beginSession()
 *   每次 write_file  → addPatch()（只收集，不弹窗）
 *   Agent 任务完成  → showReviewPanel()（统一弹出多文件确认面板）
 *
 * 用户操作：
 *   - [接受全部]      → acceptAll()  → 全部文件写入磁盘
 *   - [拒绝全部]      → rejectAll()  → 所有变更丢弃
 *   - 单文件[接受]    → acceptFile() → 该文件写入，其余待定
 *   - 单文件[拒绝]    → rejectFile() → 该文件跳过，其余待定
 *   - 点击文件名查看  → 触发 InlineDiffManager 单文件 diff
 */
object MultiFileDiffManager {

    private val log = logger<MultiFileDiffManager>()

    // ==================== 数据结构 ====================

    /**
     * 单文件变更补丁
     *
     * @param relativePath  相对项目根目录的路径（如 "src/main/.../Foo.kt"）
     * @param originalContent 原始内容（新建文件时为空字符串）
     * @param newContent    AI 生成的新内容
     * @param isNewFile     是否是新建文件
     */
    data class FilePatch(
        val relativePath: String,
        val originalContent: String,
        val newContent: String,
        val isNewFile: Boolean = false
    ) {
        /** 计算 +/- 行数（用于 UI 展示） */
        fun diffStats(): Pair<Int, Int> {
            val oldLines = originalContent.lines().toSet()
            val newLines = newContent.lines().toSet()
            val added = newContent.lines().count { it !in oldLines }
            val deleted = originalContent.lines().count { it !in newLines }
            return added to deleted
        }
    }

    /** 单文件补丁的用户决策 */
    enum class PatchDecision { PENDING, ACCEPTED, REJECTED }

    /**
     * 一次 Agent 任务的多文件变更批次
     *
     * @param taskId    Agent 任务 ID（UUID）
     * @param patches   该任务收集到的所有文件变更
     * @param decisions 每个文件的用户决策（默认 PENDING）
     * @param completed Agent 是否已完成所有工具调用（调用 showReviewPanel 后置 true）
     */
    data class MultiFileDiffSession(
        val taskId: String,
        val patches: MutableList<FilePatch> = mutableListOf(),
        val decisions: MutableMap<String, PatchDecision> = mutableMapOf(),
        var completed: Boolean = false
    ) {
        /** 待用户决策的 patch 数量 */
        fun pendingCount() = decisions.values.count { it == PatchDecision.PENDING }
        /** 已接受的 patch 数量 */
        fun acceptedCount() = decisions.values.count { it == PatchDecision.ACCEPTED }
    }

    /** 全局 session 存储（taskId → session） */
    private val sessions = ConcurrentHashMap<String, MultiFileDiffSession>()

    // ==================== 生命周期 API ====================

    /**
     * Agent 任务开始时创建批次
     *
     * @param taskId 唯一 ID，建议用 UUID.randomUUID().toString()
     * @return 新建的 session
     */
    fun beginSession(taskId: String): MultiFileDiffSession {
        val session = MultiFileDiffSession(taskId = taskId)
        sessions[taskId] = session
        log.info("MultiFileDiff: 开始会话 [$taskId]")
        return session
    }

    /**
     * Agent 调用 write_file 时收集补丁（不立即展示）
     *
     * @param taskId        当前 Agent 任务 ID
     * @param patch         文件变更补丁
     */
    fun addPatch(taskId: String, patch: FilePatch) {
        val session = sessions[taskId] ?: run {
            log.warn("MultiFileDiff: 找不到会话 [$taskId]，已忽略 patch: ${patch.relativePath}")
            return
        }
        // 同一文件多次 write_file → 用最新内容覆盖
        val existing = session.patches.indexOfFirst { it.relativePath == patch.relativePath }
        if (existing >= 0) {
            session.patches[existing] = patch
            log.debug("MultiFileDiff: 更新 patch [${patch.relativePath}]")
        } else {
            session.patches.add(patch)
            log.debug("MultiFileDiff: 收集 patch [${patch.relativePath}]（共 ${session.patches.size} 个）")
        }
        // 初始化决策状态
        session.decisions[patch.relativePath] = PatchDecision.PENDING
    }

    /**
     * Agent 任务完成后，弹出统一的多文件确认面板
     *
     * @param project 当前项目
     * @param taskId  Agent 任务 ID
     */
    fun showReviewPanel(project: Project, taskId: String) {
        val session = sessions[taskId] ?: run {
            log.warn("MultiFileDiff: 找不到会话 [$taskId]，无法展示确认面板")
            return
        }
        session.completed = true

        if (session.patches.isEmpty()) {
            log.info("MultiFileDiff: 会话 [$taskId] 无文件变更，跳过面板")
            sessions.remove(taskId)
            return
        }

        log.info("MultiFileDiff: 展示确认面板，共 ${session.patches.size} 个文件变更")

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            MultiFileDiffPanel.show(project, session,
                onAcceptAll  = { acceptAll(project, taskId) },
                onRejectAll  = { rejectAll(taskId) },
                onAcceptFile = { path -> acceptFile(project, taskId, path) },
                onRejectFile = { path -> rejectFile(taskId, path) },
                onViewFile   = { path -> viewFile(project, taskId, path) }
            )
        }
    }

    // ==================== 用户决策 API ====================

    /**
     * 接受全部变更：将所有 patch 写入磁盘
     */
    fun acceptAll(project: Project, taskId: String) {
        val session = sessions[taskId] ?: return
        session.patches.forEach { patch ->
            if (session.decisions[patch.relativePath] == PatchDecision.PENDING) {
                applyPatch(project, patch)
                session.decisions[patch.relativePath] = PatchDecision.ACCEPTED
            }
        }
        log.info("MultiFileDiff: 接受全部变更（${session.patches.size} 个文件）")
        sessions.remove(taskId)
    }

    /**
     * 拒绝全部变更：丢弃所有 patch，不写入任何文件
     */
    fun rejectAll(taskId: String) {
        val session = sessions[taskId] ?: return
        session.decisions.keys.forEach { session.decisions[it] = PatchDecision.REJECTED }
        log.info("MultiFileDiff: 拒绝全部变更（${session.patches.size} 个文件）")
        sessions.remove(taskId)
    }

    /**
     * 接受单个文件变更：写入该文件，其余保持 PENDING
     */
    fun acceptFile(project: Project, taskId: String, relativePath: String) {
        val session = sessions[taskId] ?: return
        val patch = session.patches.find { it.relativePath == relativePath } ?: return
        applyPatch(project, patch)
        session.decisions[relativePath] = PatchDecision.ACCEPTED
        log.info("MultiFileDiff: 接受 [$relativePath]，剩余 ${session.pendingCount()} 个待决策")
        cleanupIfDone(taskId, session)
    }

    /**
     * 拒绝单个文件变更：跳过该文件，其余保持 PENDING
     */
    fun rejectFile(taskId: String, relativePath: String) {
        val session = sessions[taskId] ?: return
        session.decisions[relativePath] = PatchDecision.REJECTED
        log.info("MultiFileDiff: 拒绝 [$relativePath]，剩余 ${session.pendingCount()} 个待决策")
        cleanupIfDone(taskId, session)
    }

    /**
     * 点击文件名：在编辑器中打开单文件 Inline Diff 预览
     */
    fun viewFile(project: Project, taskId: String, relativePath: String) {
        val session = sessions[taskId] ?: return
        val patch = session.patches.find { it.relativePath == relativePath } ?: return

        val root = com.intellij.openapi.roots.ProjectRootManager
            .getInstance(project).contentRoots.firstOrNull() ?: return
        val virtualFile = root.findFileByRelativePath(relativePath) ?: run {
            log.warn("MultiFileDiff: 文件不存在，无法预览 [$relativePath]")
            return
        }

        log.info("MultiFileDiff: 预览 Inline Diff [$relativePath]")
        InlineDiffManager.showInlineDiff(project, virtualFile, patch.newContent)
    }

    /**
     * 查询 session（供 MultiFileDiffPanel 等组件读取）
     */
    fun getSession(taskId: String): MultiFileDiffSession? = sessions[taskId]

    // ==================== 内部工具 ====================

    /**
     * 将 patch 写入磁盘
     * - 已有文件：直接覆写内容（WriteCommandAction）
     * - 新建文件：创建目录 + 文件
     */
    private fun applyPatch(project: Project, patch: FilePatch) {
        try {
            val root = com.intellij.openapi.roots.ProjectRootManager
                .getInstance(project).contentRoots.firstOrNull() ?: return

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    if (patch.isNewFile) {
                        // 新建文件：确保父目录存在
                        val parentPath = patch.relativePath.substringBeforeLast('/', "")
                        val parentDir = if (parentPath.isBlank()) root
                        else com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(root, parentPath)

                        parentDir?.createChildData(this, patch.relativePath.substringAfterLast('/'))
                            ?.setBinaryContent(patch.newContent.toByteArray(Charsets.UTF_8))
                    } else {
                        // 已有文件：找到并覆写
                        val vf = root.findFileByRelativePath(patch.relativePath)
                        vf?.setBinaryContent(patch.newContent.toByteArray(Charsets.UTF_8))

                        // 刷新编辑器中已打开的文档
                        val doc = vf?.let {
                            com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().getDocument(it)
                        }
                        doc?.let {
                            com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().saveDocument(it)
                        }
                    }
                }
            }
            log.info("MultiFileDiff: 已应用 patch [${ patch.relativePath}]")
        } catch (e: Exception) {
            log.error("MultiFileDiff: 应用 patch 失败 [${patch.relativePath}]", e)
        }
    }

    /** 所有文件都已决策时自动清理 session */
    private fun cleanupIfDone(taskId: String, session: MultiFileDiffSession) {
        if (session.pendingCount() == 0) {
            log.info("MultiFileDiff: 会话 [$taskId] 全部决策完毕（接受 ${session.acceptedCount()} 个），清理会话")
            sessions.remove(taskId)
        }
    }

    // ==================== 工具函数 ====================

    /** 生成唯一任务 ID */
    fun newTaskId(): String = UUID.randomUUID().toString()
}
