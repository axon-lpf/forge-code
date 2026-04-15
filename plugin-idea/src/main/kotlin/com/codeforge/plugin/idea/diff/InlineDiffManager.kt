package com.codeforge.plugin.idea.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/**
 * Inline Diff 管理器
 *
 * 在编辑器中直接显示行级增删高亮：
 *  - 绿色背景：AI 新增的行
 *  - 红色背景：将被删除的行（通过 Highlighter 标记）
 *  - Gutter 图标：+/- 标记
 *  - 支持 Accept All / Reject All / 单块操作
 */
object InlineDiffManager {

    private val log = logger<InlineDiffManager>()

    /** 每个编辑器的活跃 Inline Diff 会话 */
    private val activeSessions = mutableMapOf<Editor, InlineDiffSession>()

    // 颜色定义
    private val ADD_BG = Color(0x1A, 0x4D, 0x2E)      // 深绿背景
    private val ADD_GUTTER = Color(0x2E, 0xA0, 0x43)   // 绿色 Gutter 条
    private val DEL_BG = Color(0x4D, 0x1A, 0x1A)       // 深红背景
    private val DEL_GUTTER = Color(0xC0, 0x3B, 0x3B)   // 红色 Gutter 条
    private val PENDING_BG = Color(0x3A, 0x3A, 0x1A)   // 待确认黄色背景

    /** 变更块类型 */
    enum class ChangeType { ADD, DELETE, MODIFY }

    /** 单个变更块 */
    data class DiffHunk(
        val type: ChangeType,
        val oldStartLine: Int,  // 原始文件行号（0-based）
        val oldLines: List<String>,
        val newLines: List<String>,
        var accepted: Boolean? = null  // null=pending, true=accepted, false=rejected
    )

    /** 一次 Inline Diff 会话 */
    class InlineDiffSession(
        val editor: Editor,
        val project: Project,
        val virtualFile: VirtualFile,
        val originalContent: String,
        val newContent: String,
        val hunks: List<DiffHunk>,
        val highlighters: MutableList<RangeHighlighter> = mutableListOf()
    )

    // ==================== 公开 API ====================

    /**
     * 显示 Inline Diff：在编辑器中标记所有变更
     *
     * @param project 当前项目
     * @param virtualFile 目标文件
     * @param newContent AI 生成的新内容
     */
    fun showInlineDiff(project: Project, virtualFile: VirtualFile, newContent: String) {
        ApplicationManager.getApplication().invokeLater {
            // 1. 打开文件获取 Editor
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
                log.warn("无法获取编辑器: ${virtualFile.path}")
                return@invokeLater
            }

            val currentFile = FileDocumentManager.getInstance().getFile(editor.document)
            if (currentFile != virtualFile) {
                log.warn("编辑器文件不匹配")
                return@invokeLater
            }

            // 2. 清除之前的 session
            clearSession(editor)

            // 3. 计算 diff
            val originalContent = editor.document.text
            val hunks = computeDiff(originalContent, newContent)

            if (hunks.isEmpty()) {
                log.info("文件无变化: ${virtualFile.name}")
                return@invokeLater
            }

            // 4. 创建 session
            val session = InlineDiffSession(
                editor = editor,
                project = project,
                virtualFile = virtualFile,
                originalContent = originalContent,
                newContent = newContent,
                hunks = hunks
            )
            activeSessions[editor] = session

            // 5. 先将新内容写入编辑器（但标记为待确认）
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText(newContent)
            }

            // 6. 高亮所有变更块
            applyHighlights(session)

            // 7. 添加通知栏（Accept All / Reject All）
            showActionBanner(session)
        }
    }

    /**
     * 接受所有变更（清除高亮，保留新内容）
     */
    fun acceptAll(editor: Editor) {
        val session = activeSessions[editor] ?: return
        clearHighlights(session)
        activeSessions.remove(editor)

        // 保存文件
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(session.project) {
                FileDocumentManager.getInstance().saveDocument(editor.document)
            }
        }
        log.info("已接受所有变更: ${session.virtualFile.name}")
    }

    /**
     * 拒绝所有变更（恢复原始内容）
     */
    fun rejectAll(editor: Editor) {
        val session = activeSessions[editor] ?: return
        clearHighlights(session)
        activeSessions.remove(editor)

        // 恢复原始内容
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(session.project) {
                editor.document.setText(session.originalContent)
            }
        }
        log.info("已拒绝所有变更: ${session.virtualFile.name}")
    }

    /**
     * 检查编辑器是否有活跃的 Inline Diff
     */
    fun hasActiveSession(editor: Editor): Boolean = activeSessions.containsKey(editor)

    /**
     * 获取活跃会话
     */
    fun getSession(editor: Editor): InlineDiffSession? = activeSessions[editor]

    // ==================== 核心：Diff 计算 ====================

    /**
     * 简单行级 Diff（Myers-like 算法简化版）
     * 使用 LCS (最长公共子序列) 方式计算变更块
     */
    private fun computeDiff(oldText: String, newText: String): List<DiffHunk> {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        val hunks = mutableListOf<DiffHunk>()

        // 计算 LCS 表格
        val m = oldLines.size
        val n = newLines.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // 回溯 LCS，收集差异
        data class DiffLine(val type: Char, val oldIdx: Int, val newIdx: Int, val line: String)
        val diffs = mutableListOf<DiffLine>()

        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                    diffs.add(0, DiffLine(' ', i - 1, j - 1, oldLines[i - 1]))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    diffs.add(0, DiffLine('+', -1, j - 1, newLines[j - 1]))
                    j--
                }
                i > 0 -> {
                    diffs.add(0, DiffLine('-', i - 1, -1, oldLines[i - 1]))
                    i--
                }
            }
        }

        // 将连续的 +/- 合并成 hunk
        var idx = 0
        while (idx < diffs.size) {
            val d = diffs[idx]
            if (d.type == ' ') {
                idx++
                continue
            }

            // 收集连续的 -/+ 行
            val delLines = mutableListOf<String>()
            val addLines = mutableListOf<String>()
            val startOldLine = if (d.type == '-') d.oldIdx else {
                // 找上一个 context 行的 oldIdx
                if (idx > 0) diffs[idx - 1].oldIdx + 1 else 0
            }

            while (idx < diffs.size && diffs[idx].type != ' ') {
                when (diffs[idx].type) {
                    '-' -> delLines.add(diffs[idx].line)
                    '+' -> addLines.add(diffs[idx].line)
                }
                idx++
            }

            val type = when {
                delLines.isNotEmpty() && addLines.isNotEmpty() -> ChangeType.MODIFY
                delLines.isNotEmpty() -> ChangeType.DELETE
                else -> ChangeType.ADD
            }

            hunks.add(DiffHunk(
                type = type,
                oldStartLine = startOldLine,
                oldLines = delLines,
                newLines = addLines
            ))
        }

        return hunks
    }

    // ==================== 高亮渲染 ====================

    /**
     * 在编辑器中应用高亮
     * 此时编辑器已经显示了新内容
     * 我们需要找到新内容中哪些行是新增/修改的并高亮
     */
    private fun applyHighlights(session: InlineDiffSession) {
        val editor = session.editor
        val markupModel = editor.markupModel
        val newLines = session.newContent.lines()
        val oldLines = session.originalContent.lines()

        // 用 diff 信息映射到新文件行号
        val m = oldLines.size
        val n = newLines.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // 回溯找出新增行
        var i = m; var j = n
        val newLineTypes = CharArray(n) { ' ' }  // ' '=unchanged, '+'=added
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> { i--; j-- }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    newLineTypes[j - 1] = '+'
                    j--
                }
                i > 0 -> { i-- }
            }
        }

        // 应用高亮
        for (lineNum in newLineTypes.indices) {
            if (newLineTypes[lineNum] != '+') continue
            if (lineNum >= editor.document.lineCount) continue

            val startOffset = editor.document.getLineStartOffset(lineNum)
            val endOffset = editor.document.getLineEndOffset(lineNum)

            // 行背景高亮（绿色）
            val hl = markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.LAST + 1,
                TextAttributes().apply {
                    backgroundColor = ADD_BG
                },
                HighlighterTargetArea.LINES_IN_RANGE
            )

            // Gutter 绿色条
            hl.lineMarkerRenderer = object : LineMarkerRenderer {
                override fun paint(ed: Editor, g: java.awt.Graphics, r: java.awt.Rectangle) {
                    g.color = ADD_GUTTER
                    g.fillRect(r.x, r.y, 3, r.height)
                }
            }

            session.highlighters.add(hl)
        }

        log.info("Inline Diff: 高亮 ${session.highlighters.size} 行变更")
    }

    /**
     * 显示操作通知条（Accept All / Reject All）
     * 使用 Editor 顶部通知面板
     */
    private fun showActionBanner(session: InlineDiffSession) {
        val editor = session.editor
        val project = session.project

        // 使用 EditorNotification 风格的自定义 Banner
        val bannerPanel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4))
        bannerPanel.background = Color(0x2B, 0x2D, 0x30)
        bannerPanel.border = javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0x44, 0x44, 0x44))

        val label = javax.swing.JLabel("  ⚡ CodeForge: AI 已修改此文件 (${session.hunks.size} 个变更块)")
        label.foreground = Color(0xBB, 0xBB, 0xBB)
        label.font = label.font.deriveFont(12f)
        bannerPanel.add(label)

        val acceptBtn = javax.swing.JButton("✅ Accept All")
        acceptBtn.font = acceptBtn.font.deriveFont(11f)
        acceptBtn.isFocusPainted = false
        acceptBtn.background = Color(0x2E, 0x7D, 0x32)
        acceptBtn.foreground = Color.WHITE
        acceptBtn.border = javax.swing.BorderFactory.createEmptyBorder(3, 12, 3, 12)
        acceptBtn.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        acceptBtn.addActionListener {
            acceptAll(editor)
            editor.headerComponent = null
        }
        bannerPanel.add(acceptBtn)

        val rejectBtn = javax.swing.JButton("✗ Reject All")
        rejectBtn.font = rejectBtn.font.deriveFont(11f)
        rejectBtn.isFocusPainted = false
        rejectBtn.background = Color(0xC6, 0x28, 0x28)
        rejectBtn.foreground = Color.WHITE
        rejectBtn.border = javax.swing.BorderFactory.createEmptyBorder(3, 12, 3, 12)
        rejectBtn.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        rejectBtn.addActionListener {
            rejectAll(editor)
            editor.headerComponent = null
        }
        bannerPanel.add(rejectBtn)

        // 设置为编辑器顶部 header
        editor.headerComponent = bannerPanel
    }

    // ==================== 清理 ====================

    private fun clearHighlights(session: InlineDiffSession) {
        for (hl in session.highlighters) {
            session.editor.markupModel.removeHighlighter(hl)
        }
        session.highlighters.clear()
        session.editor.headerComponent = null
    }

    private fun clearSession(editor: Editor) {
        val existing = activeSessions.remove(editor) ?: return
        clearHighlights(existing)
    }
}

