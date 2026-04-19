package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.agent.AgentService
import com.codeforge.plugin.idea.diff.InlineDiffManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * A7：行内编辑弹窗
 *
 * 选中代码后按 Alt+A 弹出此浮窗，用户输入修改指令或点击快捷按钮，
 * AI 返回修改结果后通过 InlineDiffManager 展示绿/红高亮供 Accept/Reject。
 */
class InlineEditPopup(
    private val project: Project,
    private val editor: Editor,
    private val virtualFile: VirtualFile,
    private val selectedText: String,
    private val selectionStart: Int,
    private val selectionEnd: Int
) {

    private var popup: JBPopup? = null

    fun show() {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        panel.preferredSize = Dimension(420, -1)

        // ── 输入框 ──────────────────────────────────────────────
        val inputField = JBTextField()
        inputField.emptyText.text = "描述修改指令，按 Enter 执行…"
        panel.add(inputField, BorderLayout.NORTH)

        // ── 快捷操作按钮行 ────────────────────────────────────────
        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        btnPanel.isOpaque = false

        listOf(
            "⚡ 优化性能" to "优化代码性能，减少不必要的计算、内存分配和循环，使用更高效的算法或数据结构",
            "📝 添加注释" to "为代码添加清晰的中文行内注释，解释关键逻辑的目的和思路，注释要说明「为什么」而非「做了什么」",
            "🐛 修复 Bug" to "分析并修复代码中的潜在 Bug、空指针、边界条件和异常处理问题"
        ).forEach { (label, instruction) ->
            btnPanel.add(makeQuickBtn(label) {
                popup?.cancel()
                executeEdit(instruction)
            })
        }

        panel.add(btnPanel, BorderLayout.CENTER)

        // ── 底部提示 ─────────────────────────────────────────────
        val hint = JLabel("Enter 执行  ·  Esc 关闭")
        hint.foreground = Color(0x88, 0x88, 0x88)
        hint.font = hint.font.deriveFont(10f)
        panel.add(hint, BorderLayout.SOUTH)

        // ── 键盘事件 ─────────────────────────────────────────────
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val instruction = inputField.text.trim()
                        if (instruction.isNotBlank()) {
                            popup?.cancel()
                            executeEdit(instruction)
                        }
                    }
                    KeyEvent.VK_ESCAPE -> popup?.cancel()
                }
            }
        })

        // ── 创建并显示弹窗 ────────────────────────────────────────
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, inputField)
            .setTitle("CodeForge — 行内 AI 编辑")
            .setFocusable(true)
            .setRequestFocus(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        // 显示在选区起始行的正下方
        val visualPos = editor.offsetToVisualPosition(selectionStart)
        val pointInEditor = editor.visualPositionToXY(visualPos)
        val popupPoint = java.awt.Point(pointInEditor.x, pointInEditor.y + editor.lineHeight + 2)
        popup!!.show(RelativePoint(editor.contentComponent, popupPoint))
    }

    // ── 私有方法 ─────────────────────────────────────────────────

    private fun makeQuickBtn(text: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0x55, 0x55, 0x55), 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
            )
            font = font.deriveFont(11f)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }
    }

    private fun executeEdit(instruction: String) {
        AgentService.runInlineEdit(
            project = project,
            selectedCode = selectedText,
            instruction = instruction,
            onResult = { newCode ->
                // 用新代码替换选中范围，然后展示 InlineDiff
                val fullContent = editor.document.text
                val newFullContent =
                    fullContent.substring(0, selectionStart) + newCode + fullContent.substring(selectionEnd)
                InlineDiffManager.showInlineDiff(project, virtualFile, newFullContent)
            },
            onError = { ex ->
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "行内编辑失败：${ex.message}",
                        "CodeForge — 行内编辑"
                    )
                }
            }
        )
    }
}
