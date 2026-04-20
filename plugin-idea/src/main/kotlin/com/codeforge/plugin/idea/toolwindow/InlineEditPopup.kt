package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.diff.InlineDiffManager
import com.codeforge.plugin.idea.service.LlmService
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * A7：行内编辑弹窗
 * 选中代码后弹出轻量输入框，用户输入修改指令，AI 处理后展示 Diff
 */
class InlineEditPopup(
    private val project: Project,
    private val selectedCode: String,
    private val language: String
) : DialogWrapper(true) {

    private val instructionArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color(100, 100, 100)),
            EmptyBorder(4, 6, 4, 6)
        )
    }

    private val quickBtns = listOf(
        "优化性能" to "优化这段代码的性能",
        "添加注释" to "为这段代码添加详细注释",
        "修复Bug" to "修复这段代码中的潜在Bug",
        "生成测试" to "为这段代码生成单元测试"
    )

    init {
        title = "Inline Edit — CodeForge"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val codePreview = JBLabel(
            "<html><b>选中的代码（${language.ifBlank { "未知语言" }}）：</b><br>" +
            "<pre style='background:#2d2d2d;padding:8px;border-radius:4px;max-height:120px;overflow:auto;'>" +
            escapeHtml(selectedCode.take(500)) +
            if (selectedCode.length > 500) "..." else "" +
            "</pre>"
        )

        val quickBtnPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4))
        quickBtns.forEach { (label, _) ->
            val btn = JButton(label)
            btn.addActionListener {
                instructionArea.text = label
            }
            quickBtnPanel.add(btn)
        }

        return FormBuilder.createFormBuilder()
            .addComponent(codePreview)
            .addComponent(JBLabel(" "))
            .addComponent(JBLabel("修改指令："))
            .addComponent(JScrollPane(instructionArea), 1)
            .addComponent(quickBtnPanel)
            .addComponent(JBLabel(
                "<html><font color='gray' size='2'>提示：AI 将直接修改选中的代码，并通过 Diff 预览确认</font></html>"
            ))
            .panel
    }

    override fun doOKAction() {
        val instruction = instructionArea.text.trim()
        if (instruction.isBlank()) {
            instructionArea.border = BorderFactory.createLineBorder(java.awt.Color(255, 80, 80))
            return
        }

        instructionArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color(100, 100, 100)),
            EmptyBorder(4, 6, 4, 6)
        )

        super.doOKAction()

        runInlineEdit(instruction)
    }

    private fun runInlineEdit(instruction: String) {
        val prompt = buildString {
            appendLine("你是一个代码优化助手。以下是用户选中的代码：")
            appendLine("```$language")
            appendLine(selectedCode)
            appendLine("```")
            appendLine()
            appendLine("用户请求：$instruction")
            appendLine()
            appendLine("请直接输出修改后的完整代码，不要包含任何解释、注释或其他文字。")
            appendLine("只输出代码，保持原有语言和格式。")
        }

        val messages = listOf(
            mapOf("role" to "user", "content" to prompt)
        )

        val buffer = StringBuilder()

        LlmService.getInstance().chatStream(
            messages = messages,
            onToken = { token ->
                buffer.append(token)
            },
            onDone = {
                val result = buffer.toString()
                    .replace("```$language", "")
                    .replace("```", "")
                    .trim()

                if (result.isNotBlank()) {
                    applyResult(result)
                }
            },
            onError = { ex ->
                javax.swing.SwingUtilities.invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "Inline Edit 失败：${ex.message}",
                        "CodeForge"
                    )
                }
            },
            onAutoRetry = { _, _, _ -> }
        )
    }

    private fun applyResult(result: String) {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
                if (virtualFile != null) {
                    InlineDiffManager.showInlineDiff(project, virtualFile, result)
                }
            }
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\n", "<br>")
    }

    companion object {
        fun show(project: Project, selectedCode: String, language: String) {
            val popup = InlineEditPopup(project, selectedCode, language)
            popup.show()
        }
    }
}
