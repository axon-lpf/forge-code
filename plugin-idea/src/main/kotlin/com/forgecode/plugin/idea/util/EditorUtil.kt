package com.forgecode.plugin.idea.util

import com.forgecode.plugin.idea.toolwindow.ForgeChatPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.google.gson.Gson

/**
 * 编辑器工具类
 */
object EditorUtil {

    private val gson = Gson()

    /**
     * 获取编辑器中选中的文本
     */
    fun getSelectedText(editor: Editor): String? {
        val selected = editor.selectionModel.selectedText
        return if (selected.isNullOrBlank()) null else selected
    }

    /**
     * 向 Forge Code 聊天面板发送消息
     * （通过 ToolWindow 找到 ForgeChatPanel，调用 executeJS）
     */
    fun sendMessageToChat(project: Project, message: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Forge Code") ?: return
        val contentManager = toolWindow.contentManager
        val content = contentManager.getContent(0) ?: return
        val component = content.component

        // 找到 ForgeChatPanel（通过组件层次结构或直接引用）
        // 这里通过用户数据存储 panel 引用
        val panel = component.getClientProperty("forgeChatPanel") as? ForgeChatPanel ?: return

        // 转义消息内容，安全地注入 JS
        val escaped = message
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
        panel.executeJS("window.setInputAndSend && window.setInputAndSend(`$escaped`)")
    }

    /**
     * 将 AI 生成的代码插入到当前编辑器光标位置
     */
    fun applyCodeToEditor(project: Project, code: String, language: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            // 没有打开的编辑器，创建新文件
            createNewFileWithCode(project, code, language)
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val caretOffset = editor.caretModel.offset
            val selectionModel = editor.selectionModel

            if (selectionModel.hasSelection()) {
                // 替换选中区域
                document.replaceString(
                    selectionModel.selectionStart,
                    selectionModel.selectionEnd,
                    code
                )
            } else {
                // 插入到光标位置
                document.insertString(caretOffset, code)
                editor.caretModel.moveToOffset(caretOffset + code.length)
            }
        }
    }

    /**
     * 创建新文件并写入代码
     */
    private fun createNewFileWithCode(project: Project, code: String, language: String) {
        val ext = languageToExtension(language)
        val fileName = "forge_generated.$ext"
        // 简单实现：显示对话框让用户选择位置
        // TODO: Phase 2 实现完整的新建文件逻辑
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "代码已复制到剪贴板，请手动粘贴到目标文件",
                "Forge Code - 应用代码"
            )
            // 复制到剪贴板
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(code), null)
        }
    }

    private fun languageToExtension(language: String): String = when (language.lowercase()) {
        "java" -> "java"
        "kotlin" -> "kt"
        "python" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "go" -> "go"
        "rust" -> "rs"
        "c++" -> "cpp"
        "c" -> "c"
        "swift" -> "swift"
        else -> "txt"
    }
}
