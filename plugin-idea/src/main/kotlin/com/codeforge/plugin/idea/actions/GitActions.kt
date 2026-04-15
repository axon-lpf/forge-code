package com.codeforge.plugin.idea.actions

import com.codeforge.plugin.idea.review.ReviewService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

/**
 * T16：Git 深度集成 — Commit Message 生成 & PR 描述生成
 *
 * - GenerateCommitMessageAction：staged diff → LLM → conventional commit → git commit
 * - GeneratePRDescriptionAction：branch diff → LLM → PR 描述 → 复制到剪贴板
 */

// ==================== Commit Message 生成 ====================

class GenerateCommitMessageAction : AnAction(
    "Generate Commit Message",
    "使用 AI 为当前 staged 变更生成 Conventional Commit 格式的提交信息",
    null
) {
    private val log = logger<GenerateCommitMessageAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 1. 检查是否有 staged 变更
        val stagedFiles = ReviewService.getChangedFiles(project)
        if (stagedFiles.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "没有检测到 staged 的文件变更。\n请先使用 git add 将文件添加到暂存区。",
                "Generate Commit Message"
            )
            return
        }

        // 2. 获取 staged diff
        val diff = ReviewService.getDiff(project, emptyList(), staged = true)
        if (diff.isBlank()) {
            Messages.showInfoMessage(
                project,
                "无法获取 staged diff 内容，请检查 git 状态。",
                "Generate Commit Message"
            )
            return
        }

        // 3. 弹出生成对话框（含进度显示 + 编辑区域）
        val dialog = CommitMessageDialog(project, diff)
        if (dialog.showAndGet()) {
            val commitMsg = dialog.getCommitMessage()
            if (commitMsg.isNotBlank()) {
                executeGitCommit(project, commitMsg)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    /**
     * 执行 git commit -m "<message>"
     */
    private fun executeGitCommit(project: Project, message: String) {
        val rootPath = project.basePath ?: return
        try {
            val args = listOf("git", "commit", "-m", message)
            val process = ProcessBuilder(args)
                .directory(java.io.File(rootPath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val exitCode = process.waitFor()

            SwingUtilities.invokeLater {
                if (exitCode == 0) {
                    Messages.showInfoMessage(
                        project,
                        "✅ Commit 成功！\n\n$output",
                        "Git Commit"
                    )
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Commit 失败（exit=$exitCode）：\n$output",
                        "Git Commit Error"
                    )
                }
            }
        } catch (ex: Exception) {
            log.error("执行 git commit 失败", ex)
            SwingUtilities.invokeLater {
                Messages.showErrorDialog(
                    project,
                    "执行 git commit 时发生异常：${ex.message}",
                    "Git Commit Error"
                )
            }
        }
    }
}

// ==================== PR 描述生成 ====================

class GeneratePRDescriptionAction : AnAction(
    "Generate PR Description",
    "使用 AI 为当前分支与目标分支的 diff 生成 PR 描述，并复制到剪贴板",
    null
) {
    private val log = logger<GeneratePRDescriptionAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 1. 询问目标 base 分支
        val baseBranch = Messages.showInputDialog(
            project,
            "请输入目标分支名称（PR 的目标，通常为 main 或 master）：",
            "Generate PR Description",
            Messages.getQuestionIcon(),
            "main",
            null
        )?.trim() ?: return

        if (baseBranch.isBlank()) return

        // 2. 获取 branch diff
        val diff = ReviewService.getBranchDiff(project, baseBranch)
        if (diff.isBlank()) {
            Messages.showInfoMessage(
                project,
                "当前分支与 '$baseBranch' 没有差异，或获取 diff 失败。\n请确认分支名称正确且 git 仓库可用。",
                "Generate PR Description"
            )
            return
        }

        // 3. 弹出 PR 描述生成对话框
        val dialog = PRDescriptionDialog(project, diff, baseBranch)
        if (dialog.showAndGet()) {
            val description = dialog.getPRDescription()
            if (description.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(description))
                Messages.showInfoMessage(
                    project,
                    "✅ PR 描述已复制到剪贴板！",
                    "Generate PR Description"
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

// ==================== Commit Message 对话框 ====================

/**
 * 生成 Commit Message 的对话框
 * - 顶部状态标签：显示生成进度
 * - 中部编辑区：可编辑生成的 commit message
 * - 底部按钮：确认提交 / 取消
 */
private class CommitMessageDialog(
    private val project: Project,
    private val diff: String
) : DialogWrapper(project) {

    private val textArea = JTextArea(8, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        text = "AI 正在生成 Commit Message，请稍候..."
        isEditable = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
    }

    private val statusLabel = JLabel("⏳ 正在调用 AI 生成...").apply {
        font = font.deriveFont(12f)
    }

    private val buffer = StringBuilder()

    init {
        title = "✨ Generate Commit Message"
        setOKButtonText("确认提交")
        setCancelButtonText("取消")
        init()
        startGeneration()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(620, 280)

        panel.add(statusLabel, BorderLayout.NORTH)

        val scrollPane = JScrollPane(textArea).apply {
            border = BorderFactory.createTitledBorder("Commit Message（可编辑）")
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        val tipLabel = JLabel(
            "<html><font color='gray' size='2'>" +
            "遵循 Conventional Commits 格式，点击\"确认提交\"执行 git commit</font></html>"
        )
        panel.add(tipLabel, BorderLayout.SOUTH)

        return panel
    }

    fun getCommitMessage(): String = textArea.text.trim()

    private fun startGeneration() {
        Thread {
            ReviewService.generateCommitMessage(
                diff = diff,
                onToken = { token ->
                    buffer.append(token)
                    SwingUtilities.invokeLater {
                        textArea.text = buffer.toString()
                        textArea.caretPosition = minOf(textArea.document.length, textArea.document.length)
                    }
                },
                onDone = {
                    SwingUtilities.invokeLater {
                        textArea.isEditable = true
                        statusLabel.text = "✅ 生成完成，可在上方编辑后点击\"确认提交\""
                    }
                },
                onError = { ex ->
                    SwingUtilities.invokeLater {
                        textArea.text = "生成失败：${ex.message}\n\n请手动输入 Commit Message。"
                        textArea.isEditable = true
                        statusLabel.text = "❌ 生成失败，请手动输入"
                    }
                }
            )
        }.start()
    }
}

// ==================== PR 描述对话框 ====================

/**
 * 生成 PR 描述的对话框
 * - 顶部状态标签：显示生成进度
 * - 中部编辑区：可编辑生成的 PR 描述（Markdown 格式）
 * - 底部按钮：复制到剪贴板 / 关闭
 */
private class PRDescriptionDialog(
    private val project: Project,
    private val diff: String,
    private val baseBranch: String
) : DialogWrapper(project) {

    private val textArea = JTextArea(16, 70).apply {
        lineWrap = true
        wrapStyleWord = true
        text = "AI 正在生成 PR 描述，请稍候..."
        isEditable = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)
    }

    private val statusLabel = JLabel("⏳ 正在生成 PR 描述...").apply {
        font = font.deriveFont(12f)
    }

    private val buffer = StringBuilder()

    init {
        title = "✨ Generate PR Description → $baseBranch"
        setOKButtonText("复制到剪贴板")
        setCancelButtonText("关闭")
        init()
        startGeneration()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(720, 420)

        panel.add(statusLabel, BorderLayout.NORTH)

        val scrollPane = JScrollPane(textArea).apply {
            border = BorderFactory.createTitledBorder("PR 描述（Markdown 格式，可编辑）")
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        val tipLabel = JLabel(
            "<html><font color='gray' size='2'>" +
            "点击\"复制到剪贴板\"后，粘贴到 GitHub / GitLab PR 描述框即可</font></html>"
        )
        panel.add(tipLabel, BorderLayout.SOUTH)

        return panel
    }

    fun getPRDescription(): String = textArea.text.trim()

    private fun startGeneration() {
        Thread {
            ReviewService.generatePRDescription(
                diff = diff,
                baseBranch = baseBranch,
                onToken = { token ->
                    buffer.append(token)
                    SwingUtilities.invokeLater {
                        textArea.text = buffer.toString()
                        textArea.caretPosition = minOf(textArea.document.length, textArea.document.length)
                    }
                },
                onDone = {
                    SwingUtilities.invokeLater {
                        textArea.isEditable = true
                        statusLabel.text = "✅ 生成完成，可在上方编辑后复制"
                    }
                },
                onError = { ex ->
                    SwingUtilities.invokeLater {
                        textArea.text = "生成失败：${ex.message}\n\n请手动输入 PR 描述。"
                        textArea.isEditable = true
                        statusLabel.text = "❌ 生成失败，请手动输入"
                    }
                }
            )
        }.start()
    }
}